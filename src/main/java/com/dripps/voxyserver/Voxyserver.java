package com.dripps.voxyserver;

import com.dripps.voxyserver.config.VoxyServerConfig;
import com.dripps.voxyserver.network.VoxyServerNetworking;
import com.dripps.voxyserver.server.*;
import com.dripps.voxyserver.util.RuntimeSwitch;
import com.dripps.voxyserver.util.VoxyUpdateChecker;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Voxyserver implements ModInitializer {
    public static final String MOD_ID = "voxyserver";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static volatile VoxyServerConfig config;
    private static volatile boolean snapshotsRequired;
    private final RuntimeSwitch<ServerRuntime> runtime = new RuntimeSwitch<>();
    private final com.dripps.voxyserver.server.background.GenerationAdmissionState generationDebt =
            new com.dripps.voxyserver.server.background.GenerationAdmissionState();
    private final Set<UUID> statsSubscribers = ConcurrentHashMap.newKeySet();
    private MinecraftServer server;
    private long lastConfigModified;
    private int configCheckCounter, statsBroadcastCounter;

    public static VoxyServerConfig getConfig() {
        return config;
    }

    /** Keep writer locking until the old worker has drained; configuration changes after stop. */
    public static boolean sourceSnapshotsEnabled() {
        return config == null || snapshotsRequired;
    }

    public LodStreamingService getStreamingService() {
        var r = runtime.get();
        return r == null ? null : r.streaming();
    }

    public WorldImportCoordinator getImportCoordinator() {
        var r = runtime.get();
        return r == null ? null : r.imports();
    }

    public com.dripps.voxyserver.server.background.BackgroundGenerationController.Stats
            getBackgroundGenerationStats() {
        var r = runtime.get();
        return r == null ? null : r.background().stats();
    }

    public void addStatsSubscriber(UUID uuid) {
        if (runtime.get() != null) statsSubscribers.add(uuid);
    }

    public boolean removeStatsSubscriber(UUID uuid) {
        return statsSubscribers.remove(uuid);
    }

    public List<String> applyConfigFromCommand(VoxyServerConfig candidate) {
        candidate.requireValid();
        List<String> notes = applyConfigChanges(candidate);
        candidate.save();
        rememberConfigTime();
        return notes;
    }

    public List<String> reloadConfigFromCommand() {
        var next = VoxyServerConfig.reload();
        if (next == null) return null;
        var notes = applyConfigChanges(next);
        rememberConfigTime();
        return notes;
    }

    @Override
    public void onInitialize() {
        config = VoxyServerConfig.load();
        VoxyServerNetworking.register();
        com.dripps.voxyserver.server.background.BackgroundChunkGeneration.registerTicketType();
        LodStreamingService.registerHandlers(this::getStreamingService);
        ChunkVoxelizer.registerHandlers(
                () -> {
                    var r = runtime.get();
                    return r == null ? null : r.voxelizer();
                });
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registry, environment) ->
                        VoxyServerCommands.register(dispatcher, this));
        ServerTickEvents.START_SERVER_TICK.register(
                s -> {
                    var r = runtime.get();
                    if (r != null) r.background().startTick();
                });
        ServerTickEvents.END_SERVER_TICK.register(this::tick);
        ServerLifecycleEvents.SERVER_STARTED.register(
                s -> {
                    server = s;
                    if (!s.isDedicatedServer()) {
                        LOGGER.info("VoxyServer disabled in singleplayer.");
                        return;
                    }
                    rememberConfigTime();
                    if (config.enabled) startRuntime();
                    else
                        LOGGER.info(
                                "VoxyServer globally disabled; no engine, workers, config polling"
                                        + " or update check started");
                });
        ServerLifecycleEvents.SERVER_STOPPING.register(
                s -> {
                    try {
                        stopRuntime();
                    } finally {
                        server = null;
                    }
                });
        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register(
                (player, origin, destination) -> {
                    var service = getStreamingService();
                    if (service != null) service.onDimensionChange(player, destination);
                });
        ServerPlayConnectionEvents.JOIN.register(
                (handler, sender, s) -> {
                    if (runtime.get() == null) return;
                    var notice = VoxyUpdateChecker.getPendingNotice();
                    var player = handler.getPlayer();
                    if (notice != null
                            && player.createCommandSourceStack()
                                    .permissions()
                                    .hasPermission(Permissions.COMMANDS_ADMIN))
                        player.sendSystemMessage(VoxyUpdateChecker.buildNoticeComponent(notice));
                });
        LOGGER.info("VoxyServer initialized (enabled={})", config.enabled);
    }

    private void startRuntime() {
        if (server == null || !server.isDedicatedServer()) return;
        try {
            snapshotsRequired = true;
            runtime.start(() -> new ServerRuntime(server, config, generationDebt));
            VoxyUpdateChecker.checkForUpdates();
            configCheckCounter = 0;
            statsBroadcastCounter = 0;
            LOGGER.info("VoxyServer runtime started");
        } catch (RuntimeException error) {
            config = config.copy();
            config.enabled = false;
            LOGGER.error("VoxyServer startup failed; runtime disabled", error);
            throw error;
        }
    }

    private void stopRuntime() {
        statsSubscribers.clear();
        try {
            runtime.stop();
        } finally {
            VoxyUpdateChecker.stop();
            configCheckCounter = 0;
            statsBroadcastCounter = 0;
        }
        snapshotsRequired = false;
        LOGGER.info("VoxyServer runtime stopped; no background jobs or config polling remain");
    }

    private void tick(MinecraftServer server) {
        var r = runtime.get();
        if (r == null)
            return; // No counters, clocks, disk polling or scheduled jobs while disabled.
        r.background().tick();
        r.streaming().onTick(server);
        r.stats().tick(server, r.streaming().sourceUpdates(), r.streaming().sentNodeCount());
        if (++configCheckCounter >= 20) {
            configCheckCounter = 0;
            checkConfigReload();
        }
        if (runtime.get() != r || statsSubscribers.isEmpty()) return;
        if (++statsBroadcastCounter < 20) return;
        statsBroadcastCounter = 0;
        Component stats = VoxyServerCommands.buildStats(server, this);
        statsSubscribers.removeIf(
                uuid -> {
                    ServerPlayer player = server.getPlayerList().getPlayer(uuid);
                    if (player == null
                            || !player.createCommandSourceStack()
                                    .permissions()
                                    .hasPermission(Permissions.COMMANDS_ADMIN)) return true;
                    player.sendSystemMessage(stats);
                    return false;
                });
    }

    private void rememberConfigTime() {
        try {
            lastConfigModified =
                    Files.getLastModifiedTime(VoxyServerConfig.getConfigPath()).toMillis();
        } catch (IOException ignored) {
        }
    }

    private void checkConfigReload() {
        try {
            var path = VoxyServerConfig.getConfigPath();
            if (!Files.exists(path)) return;
            long modified = Files.getLastModifiedTime(path).toMillis();
            if (modified == lastConfigModified) return;
            lastConfigModified = modified;
            var next = VoxyServerConfig.reload();
            if (next == null) return;
            applyConfigChanges(next);
            LOGGER.info("config reloaded successfully");
        } catch (IOException ignored) {
        }
    }

    private List<String> applyConfigChanges(VoxyServerConfig next) {
        var previous = config;
        var notes = new ArrayList<String>();
        if (!next.enabled) {
            // Drain before disabling writer monitors, even when shutdown reports a failure.
            try {
                stopRuntime();
            } finally {
                config = next;
            }
            return notes;
        }
        config = next;
        var active = runtime.get();
        if (active == null) {
            startRuntime();
            if (server != null && !server.isDedicatedServer())
                notes.add("The server runtime supports dedicated servers only");
            return notes;
        }
        if (previous.generateOnChunkLoad != next.generateOnChunkLoad)
            notes.add("generateOnChunkLoad requires runtime restart (enabled false, then true)");
        if (previous.generateOnChunkUnload != next.generateOnChunkUnload)
            notes.add("generateOnChunkUnload requires runtime restart (enabled false, then true)");
        if (previous.dirtyTrackingEnabled != next.dirtyTrackingEnabled)
            notes.add("dirtyTrackingEnabled requires runtime restart (enabled false, then true)");
        active.updateConfig(previous, next);
        return notes;
    }
}
