package com.dripps.voxyserver.server.background;

import com.dripps.voxyserver.Voxyserver;
import com.dripps.voxyserver.config.VoxyServerConfig;
import com.dripps.voxyserver.server.LodStreamingService;
import com.dripps.voxyserver.util.BoundedLruCache;

import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.level.ChunkPos;

import java.util.*;
import java.util.concurrent.*;

/** Low-frequency, bounded native world-generation admission. Called only on the server thread. */
public final class BackgroundChunkGeneration {
    private static TicketType ticketType;

    /**
     * Register before registries freeze. Loading only: no entity/block ticking or persistent
     * force-load.
     */
    public static void registerTicketType() {
        if (ticketType == null)
            ticketType =
                    Registry.register(
                            BuiltInRegistries.TICKET_TYPE,
                            Identifier.parse("voxyserver:background_generation"),
                            new TicketType(1201, TicketType.FLAG_LOADING));
    }

    public static TicketType ticketType() {
        return ticketType;
    }

    private record Key(Identifier dimension, long position) {}

    private record Target(UUID player, ServerLevel level, ChunkPos position) {}

    private final MinecraftServer server;
    private final LodStreamingService streaming;
    private VoxyServerConfig config;
    private BoundedLruCache<Key, Long> history;
    private final Set<Key> ownedTickets = new HashSet<>();
    private final Random random = new Random();
    private final BackgroundGenerationController<Target> controller;
    private long tick, tickStarted;
    private int playerCursor;
    private String lastState = "disabled";

    public BackgroundChunkGeneration(
            MinecraftServer server, LodStreamingService streaming, VoxyServerConfig cfg) {
        if (ticketType == null)
            throw new IllegalStateException("background ticket type not registered");
        this.server = server;
        this.streaming = streaming;
        config = cfg.copy();
        history = new BoundedLruCache<>(Math.max(1, cfg.backgroundGenerationHistorySize));
        controller = new BackgroundGenerationController<>(new NativeBackend(), cfg);
    }

    public void startTick() {
        tickStarted = System.nanoTime();
    }

    private double mspt(long now) {
        double measured = tickStarted == 0 ? 0 : (now - tickStarted) / 1e6;
        return Math.max(
                measured,
                Math.max(
                        server.getAverageTickTimeNanos() / 1e6,
                        server.getCurrentSmoothedTickTime()));
    }

    public void tick() {
        tick++;
        long now = System.nanoTime();
        boolean suspended =
                server.isPaused()
                        || (config.backgroundGenerationMaxPendingLodSections > 0
                                && streaming.pendingSourceSections()
                                        > config.backgroundGenerationMaxPendingLodSections);
        try {
            controller.tick(tick, now, mspt(now), suspended);
            // Native ticket graph setup is synchronous but never awaited; detect an overrun
            // immediately after it.
            if (mspt(System.nanoTime()) > config.backgroundGenerationStopMspt)
                controller.tick(tick, System.nanoTime(), mspt(System.nanoTime()), suspended);
        } catch (RuntimeException failure) {
            controller.shutdown();
            Voxyserver.LOGGER.error(
                    "background generation stopped after scheduler failure", failure);
        }
        var stats = controller.stats();
        if (!stats.state().equals(lastState)) {
            if (config.backgroundGenerationEnabled
                    && (stats.state().equals("mspt") || lastState.equals("mspt")))
                Voxyserver.LOGGER.info(
                        "background generation state={} MSPT={} stopAbove={} inFlight={}"
                                + " draining={}",
                        stats.state(),
                        stats.mspt(),
                        config.backgroundGenerationStopMspt,
                        stats.inFlight(),
                        stats.draining());
            lastState = stats.state();
        }
        if (config.debugTrackingEnabled && tick % config.debugTrackingInterval == 0)
            Voxyserver.LOGGER.info("background generation {}", stats);
    }

    public void updateConfig(VoxyServerConfig next) {
        controller.updateConfig(next);
        if (config.backgroundGenerationHistorySize != next.backgroundGenerationHistorySize)
            history = new BoundedLruCache<>(Math.max(1, next.backgroundGenerationHistorySize));
        config = next.copy();
    }

    public void shutdown() {
        controller.shutdown();
    }

    public BackgroundGenerationController.Stats stats() {
        return controller.stats();
    }

    private Key key(Target target) {
        return new Key(target.level.dimension().identifier(), target.position.pack());
    }

    private int minimumRadius(ServerPlayer player) {
        return Math.max(
                config.backgroundGenerationMinRadiusChunks,
                Math.min(
                                server.getPlayerList().getViewDistance(),
                                Math.max(2, player.requestedViewDistance()))
                        + 2);
    }

    private boolean inRange(Target target) {
        ServerPlayer player = server.getPlayerList().getPlayer(target.player);
        if (player == null
                || player.level() != target.level
                || (!config.backgroundGenerationIncludeSpectators && player.isSpectator())
                || !config.backgroundGenerationDimensions.contains(
                        target.level.dimension().identifier().toString())) return false;
        if (!BackgroundGenerationArea.contains(
                new BackgroundGenerationArea.Point(target.position.x(), target.position.z()),
                player.getBlockX() >> 4,
                player.getBlockZ() >> 4,
                minimumRadius(player),
                config.backgroundGenerationMaxRadiusChunks)) return false;
        if (!BackgroundGenerationArea.isWorldChunk(
                new BackgroundGenerationArea.Point(target.position.x(), target.position.z())))
            return false;
        int x = target.position.x() << 4, z = target.position.z() << 4;
        return target.level.getWorldBorder().isWithinBounds(new BlockPos(x, 0, z))
                && target.level.getWorldBorder().isWithinBounds(new BlockPos(x + 15, 0, z + 15));
    }

    private final class NativeBackend implements BackgroundGenerationController.Backend<Target> {
        @Override
        public Target candidate() {
            var players = server.getPlayerList().getPlayers();
            if (players.isEmpty()) return null;
            ServerPlayer player = players.get(Math.floorMod(playerCursor++, players.size()));
            int min = minimumRadius(player), max = config.backgroundGenerationMaxRadiusChunks;
            var point =
                    BackgroundGenerationArea.sample(
                            player.getBlockX() >> 4, player.getBlockZ() >> 4, min, max, random);
            if (point == null) return null;
            Target target =
                    new Target(
                            player.getUUID(), player.level(), new ChunkPos(point.x(), point.z()));
            if (!inRange(target)
                    || target.level.getChunkSource().getChunkNow(point.x(), point.z()) != null)
                return null;
            Long previous =
                    config.backgroundGenerationHistorySize == 0
                            ? null
                            : history.get(BackgroundChunkGeneration.this.key(target));
            return previous != null
                            && System.nanoTime() - previous
                                    < TimeUnit.SECONDS.toNanos(
                                            config.backgroundGenerationRecheckSeconds)
                    ? null
                    : target;
        }

        @Override
        public Object key(Target target) {
            return BackgroundChunkGeneration.this.key(target);
        }

        @Override
        public boolean valid(Target target) {
            return inRange(target);
        }

        @Override
        public CompletableFuture<Boolean> exists(Target target) {
            if (config.backgroundGenerationHistorySize > 0)
                history.put(BackgroundChunkGeneration.this.key(target), System.nanoTime());
            // Vanilla pending-save I/O: inspect only this chunk, without loading a chunk or
            // decoding palettes on main.
            return target.level
                    .getChunkSource()
                    .chunkMap
                    .read(target.position)
                    .thenApply(
                            optional -> {
                                if (optional.isEmpty()) return false;
                                String status = optional.get().getStringOr("Status", "");
                                Identifier id = Identifier.tryParse(status);
                                if (id == null || !BuiltInRegistries.CHUNK_STATUS.containsKey(id))
                                    throw new CompletionException(
                                            new IllegalStateException(
                                                    "unknown stored chunk status at "
                                                            + target.position));
                                return status.equals("minecraft:full") || status.equals("full");
                            });
        }

        @Override
        public CompletableFuture<Boolean> generate(Target target) {
            Key key = BackgroundChunkGeneration.this.key(target);
            if (!ownedTickets.add(key))
                throw new IllegalStateException("duplicate background ticket");
            // Unlike getChunkFuture(...), this method does not managedBlock/join on the server
            // thread.
            return target.level
                    .getChunkSource()
                    .addTicketAndLoadWithRadius(ticketType, target.position, 0)
                    .thenApply(
                            result -> result instanceof ChunkResult<?> chunk && chunk.isSuccess());
        }

        @Override
        public void retain(Target target) {
            if (ownedTickets.contains(BackgroundChunkGeneration.this.key(target)))
                target.level.getChunkSource().addTicketWithRadius(ticketType, target.position, 0);
        }

        @Override
        public boolean overloaded() {
            return mspt(System.nanoTime()) > config.backgroundGenerationStopMspt;
        }

        @Override
        public boolean ready(Target target) {
            return target.level
                            .getChunkSource()
                            .getChunkNow(target.position.x(), target.position.z())
                    != null;
        }

        @Override
        public void ingest(Target target) {
            var chunk =
                    target.level
                            .getChunkSource()
                            .getChunkNow(target.position.x(), target.position.z());
            if (chunk == null)
                throw new IllegalStateException("completed background chunk disappeared");
            for (int y = chunk.getMinSectionY(); y < chunk.getMaxSectionY(); y++)
                streaming.markSourceDirty(
                        target.level, target.position.x() << 4, y << 4, target.position.z() << 4);
        }

        @Override
        public void release(Target target) {
            if (ownedTickets.remove(BackgroundChunkGeneration.this.key(target)))
                target.level
                        .getChunkSource()
                        .removeTicketWithRadius(ticketType, target.position, 0);
        }
    }
}
