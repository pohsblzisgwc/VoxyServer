package com.dripps.voxyserver.server;

import com.dripps.voxyserver.Voxyserver;
import com.dripps.voxyserver.network.LODBulkPayload;
import com.dripps.voxyserver.network.LODBatchResultPayload;
import com.dripps.voxyserver.network.LODClearPayload;
import com.dripps.voxyserver.network.LODHandshakePayload;
import com.dripps.voxyserver.network.LODHashSyncSettingsPayload;
import com.dripps.voxyserver.network.LODManifestPayload;
import com.dripps.voxyserver.network.ManifestScope;
import com.dripps.voxyserver.network.LODManifestAckPayload;
import com.dripps.voxyserver.network.LODPreferencesPayload;
import com.dripps.voxyserver.network.LODProtocolPayload;
import com.dripps.voxyserver.network.LODSectionPayload;
import com.dripps.voxyserver.network.LODServerSettingsPayload;
import com.dripps.voxyserver.network.LODStreamSessionPayload;
import com.dripps.voxyserver.network.PreSerializedLodPayload;
import com.dripps.voxyserver.network.VoxyServerNetworking;
import com.dripps.voxyserver.config.VoxyServerConfig;
import com.dripps.voxyserver.network.LODRegionSyncPayload;
import com.dripps.voxyserver.server.hash.RegionHashManager;
import com.dripps.voxyserver.server.throttle.BandwidthThrottler;
import com.dripps.voxyserver.server.throttle.PlayerOutboundQueue;
import com.dripps.voxyserver.util.IdRemapper;
import com.dripps.voxyserver.util.SemanticSectionHash;
import com.dripps.voxyserver.util.SemanticPalette;
import com.dripps.voxyserver.util.HashWorkBudget;
import com.dripps.voxyserver.util.BoundedLruCache;
import it.unimi.dsi.fastutil.longs.Long2ShortOpenHashMap;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.biome.Biome;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class LodStreamingService {
    private static final long IDLE_SCAN_RESTART_TICKS = 100L;
    private static final long INITIAL_LOAD_GRACE_TICKS = 20L;
    private static final int INITIAL_LOAD_MIN_CHUNKS_AT_DEADLINE = 3;
    private static final int MAX_DIRTY_SECTIONS_PER_DRAIN = 64;
    private static final int MAX_MANIFEST_BATCHES_PER_DRAIN = 4;
    private static final int MAX_SCAN_CANDIDATES_PER_SNAPSHOT = 16_384;
    private static final int MAX_ACQUIRE_ATTEMPTS_PER_SNAPSHOT = 256;
    private static final int MAX_MISSING_ACQUIRES_PER_SNAPSHOT = 16;
    // we have a grace window for the client manifest to arrive before the scan falls back to a full send
    private static final long MANIFEST_TIMEOUT_TICKS = 60L;
    private static final long BATCH_ACK_TIMEOUT_TICKS = 600L;

    private volatile VoxyServerConfig config;
    private final ServerLodEngine engine;
    private volatile int lodStreamRadius;
    private volatile int maxSectionsPerTick;
    private volatile int sectionsPerPacket;
    private volatile int tickInterval;
    private volatile long pendingDirtyTimeoutTicks;
    private volatile boolean hashSyncEnabled;
    private final DimensionOrdinals dimOrdinals = new DimensionOrdinals();
    private final ConcurrentHashMap<UUID, PlayerLodTracker> trackers = new ConcurrentHashMap<>();
    private final BoundedLruCache<Long, Integer> sectionVersions = new BoundedLruCache<>(16_384);
    private final java.util.concurrent.atomic.AtomicInteger nextSectionRevision = new java.util.concurrent.atomic.AtomicInteger();
    private long lastHashWindow = Long.MIN_VALUE;
    private int firstSnapshot;
    private final BoundedLruCache<Long, long[]> hashCacheByKey = new BoundedLruCache<>(16_384);
    private final BoundedLruCache<Long, SectionSnapshot> sectionSnapshots = new BoundedLruCache<>(128);
    private final HashWorkBudget hashWorkBudget = new HashWorkBudget();
    private record SectionSnapshot(int version, long tick, LODSectionPayload payload) {}
    private final ConcurrentHashMap<Long, Long> pendingDirtySections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Long> initialLoadSections = new ConcurrentHashMap<>();
    private final Set<Long> loadedChunks = ConcurrentHashMap.newKeySet();
    private final Set<Long> queuedDirtySections = ConcurrentHashMap.newKeySet();
    private final Queue<ManifestBatch> pendingManifestBatches = new java.util.concurrent.ArrayBlockingQueue<>(256);
    private final Set<UUID> pendingManifestPlayers = ConcurrentHashMap.newKeySet();
    private long lastManifestDrainTick = Long.MIN_VALUE;
    private final AtomicReference<SnapshotBatch> pendingSnapshotBatch = new AtomicReference<>();
    private final AtomicBoolean streamWorkerScheduled = new AtomicBoolean();
    private volatile ExecutorService streamExecutor = createStreamExecutor();
    private volatile MinecraftServer server;
    private int tickCounter = 0;
    private volatile long currentTick = 0L;

    // biome id -> vanilla registry id cache per mapper, only accessed from stream thread
    private final IdentityHashMap<Mapper, int[]> biomeIdCaches = new IdentityHashMap<>();

    // we try to recover from voxy state corruption as best we can but the fix is rlly in voxy, all i can do for now.
    private final Set<Identifier> corruptedDimensions = ConcurrentHashMap.newKeySet();
    private volatile long lastStreamHeartbeat = System.nanoTime();
    private volatile Identifier currentStreamDimension;
    private static final long STREAM_WORKER_STUCK_NANOS = TimeUnit.SECONDS.toNanos(30);

    private final java.util.concurrent.atomic.LongAdder cacheHits = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder hashesComputed = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder sendsSkipped = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder transmittedSections = new java.util.concurrent.atomic.LongAdder();

    private record SnapshotBatch(MinecraftServer server, List<PlayerSnapshot> snapshots) {}
    private record ManifestBatch(UUID playerId, Identifier dimension,
                                 long streamEpoch, long requestId,
                                 long[] keys, long[] hashes, int status, long pageId) {}

    private static final class DimensionOrdinals {
        private final ConcurrentHashMap<Identifier, Integer> dimToOrdinal = new ConcurrentHashMap<>();
        private volatile Identifier[] ordinalToDim = new Identifier[0];

        int getOrdinal(Identifier dim) {
            Integer ord = dimToOrdinal.get(dim);
            if (ord != null) return ord;
            return register(dim);
        }

        private synchronized int register(Identifier dim) {
            Integer ord = dimToOrdinal.get(dim);
            if (ord != null) return ord;
            int o = ordinalToDim.length;
            if (o >= 16) throw new IllegalStateException("too many dimensions for key encoding (max 16)");
            Identifier[] newArr = Arrays.copyOf(ordinalToDim, o + 1);
            newArr[o] = dim;
            ordinalToDim = newArr;
            dimToOrdinal.put(dim, o);
            return o;
        }

        Identifier getDimension(int ordinal) {
            return ordinalToDim[ordinal];
        }
    }

    // packs dimension ordinal into the unused level bits of a level 0 section key
    static long composeSectionKey(int dimOrdinal, long sectionKey) {
        return sectionKey | ((long)(dimOrdinal & 0xF) << 60);
    }

    static long extractSectionKey(long compositeKey) {
        return compositeKey & 0x0FFFFFFFFFFFFFFFL;
    }

    static int extractSectionDimOrdinal(long compositeKey) {
        return (int)(compositeKey >>> 60) & 0xF;
    }

    private static long composeChunkKey(int dimOrdinal, int chunkX, int chunkZ) {
        return ((long)(dimOrdinal & 0xFF) << 56) | ((long)(chunkX & 0x0FFFFFFF) << 28) | (chunkZ & 0x0FFFFFFFL);
    }

    private static Component clientOutOfDateMessage() {
        return Component.literal("[VoxyServer] ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                .append(Component.literal("your VoxyServer client is out of date. update the mod to use LODs from this server.")
                        .withStyle(ChatFormatting.RED));
    }

    public LodStreamingService(ServerLodEngine engine, VoxyServerConfig config) {
        this.config = config;
        this.engine = engine;
        this.lodStreamRadius = config.lodStreamRadius;
        this.maxSectionsPerTick = config.maxSectionsPerTickPerPlayer;
        this.sectionsPerPacket = config.sectionsPerPacket;
        this.tickInterval = config.tickInterval;
        this.pendingDirtyTimeoutTicks = Math.max(config.dirtyTrackingInterval * 2L, 40L);
        this.hashSyncEnabled = config.hashSyncEnabled;
        this.engine.setDirtySectionListener(this::onWorldSectionDirty);
    }

    public void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            var tracker = new PlayerLodTracker();
            trackers.put(handler.getPlayer().getUUID(), tracker);
            BandwidthThrottler.INSTANCE.registerPlayer(
                    handler.getPlayer().getUUID(), config.maxBandwidthBytesPerSec);
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID playerId = handler.getPlayer().getUUID();
            var tracker = trackers.remove(playerId);
            List<PlayerOutboundQueue.QueuedPacket> dropped = BandwidthThrottler.INSTANCE.unregisterPlayer(playerId);
            if (tracker != null) {
                for (PlayerOutboundQueue.QueuedPacket pkt : dropped) {
                    tracker.completeBatch(pkt.streamEpoch(), pkt.batchId(), false, currentTick);
                }
                tracker.reset();
            }
        });

        ServerPlayNetworking.registerGlobalReceiver(LODHandshakePayload.TYPE, (payload, context) -> {
            var tracker = trackers.get(context.player().getUUID());
            if (tracker == null) return;
            ServerPlayer player = context.player();
            int clientProto = payload.protocol();
            int serverProto = VoxyServerNetworking.PROTOCOL_VERSION;
            tracker.setReady(true);
            if (clientProto == serverProto) {
                tracker.setProtocolOk(true);
                if (ServerPlayNetworking.canSend(player, LODProtocolPayload.TYPE)) {
                    ServerPlayNetworking.send(player, new LODProtocolPayload(serverProto));
                }
                sendSettings(player);
                sendNewSession(player, tracker, player.level().dimension().identifier());
                Voxyserver.LOGGER.info("player {} ready for LOD streaming, protocol {}", player.getName().getString(), serverProto);
            } else if (clientProto < serverProto) {
                tracker.setProtocolOk(false);
                Voxyserver.LOGGER.warn("player {} VoxyServer client out of date (client protocol {}, server {}), LOD streaming disabled",
                        player.getName().getString(), clientProto, serverProto);
                player.sendSystemMessage(clientOutOfDateMessage());
            } else {
                tracker.setProtocolOk(false);
                Voxyserver.LOGGER.warn("this server's VoxyServer is out of date (server protocol {}, client {}) for player {}, LOD streaming disabled",
                        serverProto, clientProto, player.getName().getString());
            }
        });

        ServerPlayNetworking.registerGlobalReceiver(LODManifestPayload.TYPE, (payload, context) -> {
            var tracker = trackers.get(context.player().getUUID());
            if (tracker == null) return;
            Identifier currentDimension = context.player().level().dimension().identifier();
            if (!currentDimension.equals(payload.dimension())) return;
            if (!tracker.matchesManifest(payload.dimension(), payload.streamEpoch(), payload.requestId())) return;
            for (long key : payload.keys()) {
                if (!tracker.containsManifestKey(key)) {
                    tracker.failManifest(payload.dimension(), payload.streamEpoch(), payload.requestId());
                    ServerPlayNetworking.send(context.player(), new LODManifestAckPayload(payload.dimension(),
                            payload.streamEpoch(), payload.requestId(), payload.pageId(), LODManifestAckPayload.ABORT));
                    return;
                }
            }
            WorldIdentifier worldId = WorldIdentifier.of(context.player().level());
            if (worldId == null) {
                ServerPlayNetworking.send(context.player(), new LODManifestAckPayload(payload.dimension(),
                        payload.streamEpoch(), payload.requestId(), payload.pageId(), true));
                return;
            }
            if (!pendingManifestPlayers.add(context.player().getUUID())) return;
            boolean accepted = pendingManifestBatches.offer(new ManifestBatch(
                    context.player().getUUID(), payload.dimension(), payload.streamEpoch(), payload.requestId(),
                    payload.keys(), payload.hashes(), payload.status(), payload.pageId()));
            if (!accepted) {
                pendingManifestPlayers.remove(context.player().getUUID());
                ServerPlayNetworking.send(context.player(), new LODManifestAckPayload(payload.dimension(),
                        payload.streamEpoch(), payload.requestId(), payload.pageId(), true));
            }
            scheduleStreamWorker();
        });

        ServerPlayNetworking.registerGlobalReceiver(LODBatchResultPayload.TYPE, (payload, context) -> {
            var tracker = trackers.get(context.player().getUUID());
            if (tracker == null) return;
            tracker.completeBatch(
                    payload.streamEpoch(),
                    payload.batchId(),
                    payload.status() == LODBatchResultPayload.SUCCESS,
                    currentTick
            );
        });

        ServerPlayNetworking.registerGlobalReceiver(LODPreferencesPayload.TYPE, (payload, context) -> {
            var tracker = trackers.get(context.player().getUUID());
            if (tracker == null) return;
            boolean wasEnabled = tracker.isLodEnabled();
            tracker.setLodEnabled(payload.enabled());
            tracker.setPreferredRadius(payload.lodStreamRadius());
            tracker.setPreferredMaxSections(payload.maxSectionsPerTick());
            if (!payload.enabled()) {
                tracker.reset();
            } else if (!wasEnabled && tracker.isProtocolOk()) {
                sendNewSession(context.player(), tracker, context.player().level().dimension().identifier());
            }
            Voxyserver.LOGGER.info("player {} updated LOD preferences: enabled={}, radius={}, maxSections={}",
                    context.player().getName().getString(), payload.enabled(),
                    payload.lodStreamRadius(), payload.maxSectionsPerTick());
        });

        ServerPlayNetworking.registerGlobalReceiver(LODRegionSyncPayload.TYPE, (payload, context) -> {
            if (!config.enableRegionHashSync) return;
            var tracker = trackers.get(context.player().getUUID());
            if (tracker != null) {
                tracker.updateClientRegionHashes(payload.regionKeys(), payload.regionHashes());
                Voxyserver.LOGGER.debug("player {} synchronized {} cached region hashes",
                        context.player().getName().getString(), payload.regionKeys().length);
            }
        });

        ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);
    }

    public void markChunkPendingDirty(Identifier dimension, int chunkX, int sectionY, int chunkZ) {
        long key = WorldEngine.getWorldSectionId(0, chunkX >> 1, sectionY, chunkZ >> 1);
        long blockUntilTick = currentTick + pendingDirtyTimeoutTicks;
        pendingDirtySections.put(composeSectionKey(dimOrdinals.getOrdinal(dimension), key), blockUntilTick);
    }

    public void markChunkPendingInitialLoad(Identifier dimension, int chunkX, int sectionY, int chunkZ) {
        long key = WorldEngine.getWorldSectionId(0, chunkX >> 1, sectionY, chunkZ >> 1);
        long compositeKey = composeSectionKey(dimOrdinals.getOrdinal(dimension), key);
        long blockUntilTick = currentTick + pendingDirtyTimeoutTicks;
        pendingDirtySections.put(compositeKey, blockUntilTick);
        long graceUntilTick = currentTick + INITIAL_LOAD_GRACE_TICKS;
        initialLoadSections.compute(compositeKey, (ignored, currentDeadline) ->
                currentDeadline == null ? graceUntilTick : Math.max(currentDeadline, graceUntilTick));
    }

    public void clearChunkPendingDirty(Identifier dimension, int chunkX, int sectionY, int chunkZ) {
        long key = WorldEngine.getWorldSectionId(0, chunkX >> 1, sectionY, chunkZ >> 1);
        long compositeKey = composeSectionKey(dimOrdinals.getOrdinal(dimension), key);
        pendingDirtySections.remove(compositeKey);
        initialLoadSections.remove(compositeKey);
    }

    public void onChunkLoadStateChanged(Identifier dimension, int chunkX, int chunkZ, boolean loaded) {
        long chunkKey = composeChunkKey(dimOrdinals.getOrdinal(dimension), chunkX, chunkZ);
        if (loaded) {
            loadedChunks.add(chunkKey);
        } else {
            loadedChunks.remove(chunkKey);
        }
    }

    public void updateConfig(VoxyServerConfig newConfig) {
        boolean hashSyncChanged = this.hashSyncEnabled != newConfig.hashSyncEnabled;
        this.config = newConfig;
        this.lodStreamRadius = newConfig.lodStreamRadius;
        this.maxSectionsPerTick = newConfig.maxSectionsPerTickPerPlayer;
        this.sectionsPerPacket = newConfig.sectionsPerPacket;
        this.tickInterval = newConfig.tickInterval;
        this.pendingDirtyTimeoutTicks = Math.max(newConfig.dirtyTrackingInterval * 2L, 40L);
        this.hashSyncEnabled = newConfig.hashSyncEnabled;
        BandwidthThrottler.INSTANCE.updateAllBandwidth(newConfig.maxBandwidthBytesPerSec);
        if (server != null) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                PlayerLodTracker tracker = trackers.get(player.getUUID());
                if (tracker == null || !tracker.isReady() || !tracker.isProtocolOk()) continue;
                sendSettings(player);
                if (hashSyncChanged) {
                    sendNewSession(player, tracker, player.level().dimension().identifier());
                }
            }
        }
    }

    public void updateConfig(int lodStreamRadius, int maxSectionsPerTick,
                             int sectionsPerPacket, int tickInterval,
                             int dirtyTrackingInterval, boolean hashSyncEnabled) {
        if (this.config != null) {
            this.config.lodStreamRadius = lodStreamRadius;
            this.config.maxClientVoxyRadius = lodStreamRadius;
            this.config.maxSectionsPerTickPerPlayer = maxSectionsPerTick;
            this.config.sectionsPerPacket = sectionsPerPacket;
            this.config.tickInterval = tickInterval;
            this.config.dirtyTrackingInterval = dirtyTrackingInterval;
            this.config.hashSyncEnabled = hashSyncEnabled;
            updateConfig(this.config);
        }
    }

    private void sendSettings(ServerPlayer player) {
        if (ServerPlayNetworking.canSend(player, LODHashSyncSettingsPayload.TYPE)) {
            ServerPlayNetworking.send(player, new LODHashSyncSettingsPayload(hashSyncEnabled));
        }
        if (ServerPlayNetworking.canSend(player, LODServerSettingsPayload.TYPE)) {
            ServerPlayNetworking.send(player, new LODServerSettingsPayload(config.maxClientVoxyRadius, maxSectionsPerTick));
        }
    }

    private void sendNewSession(ServerPlayer player, PlayerLodTracker tracker, Identifier dimension) {
        if (!tracker.isReady() || !tracker.isProtocolOk()) return;
        PlayerLodTracker.StreamSession session = tracker.beginSession(
                dimension,
                true, // bounded inventory reconciliation remains useful when content reuse is disabled
                currentTick + MANIFEST_TIMEOUT_TICKS,
                new ManifestScope(player.getBlockX() >> 5, player.getBlockZ() >> 5,
                        tracker.getEffectiveRadius(config.maxClientVoxyRadius) >> 1), dimOrdinals.getOrdinal(dimension)
        );
        tracker.recordManifestArea(player.getBlockX() >> 5, player.getBlockZ() >> 5,
                tracker.getEffectiveRadius(config.maxClientVoxyRadius) >> 1, currentTick, config.manifestRefreshIntervalTicks);
        if (ServerPlayNetworking.canSend(player, LODStreamSessionPayload.TYPE)) {
            ServerPlayNetworking.send(player, new LODStreamSessionPayload(
                    session.dimension(),
                    session.streamEpoch(),
                    session.manifestRequestId(),
                    session.manifestRequired(), engine.getWorldIdentity(), SemanticSectionHash.VERSION, tracker.manifestScope()
            ));
        }
    }

    public void shutdown() {
        pendingManifestBatches.clear();
        pendingManifestPlayers.clear();
        streamExecutor.shutdownNow();
        try {
            streamExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    public record PlayerStat(String name, int sentCount, int chunkX, int chunkZ) {}

    public record StreamingStats(int players, int trackedSections, int hashCacheSize,
                                 int pendingDirty, int queuedDirty, int loadedChunks,
                                 List<PlayerStat> perPlayer) {}

    public void logSyncStats() {
        Voxyserver.LOGGER.info("sync: cacheHits={} hashesComputed={} skipped={} transmittedSections={}",
                cacheHits.sum(), hashesComputed.sum(), sendsSkipped.sum(), transmittedSections.sum());
        trackers.forEach((id, tracker) -> {
            var queue = BandwidthThrottler.INSTANCE.getQueue(id);
            var stats = tracker.syncStats();
            Voxyserver.LOGGER.info("sync player {}: {} deliveryFailureRate={} queue={}",
                    id, stats, stats.deliveryFailureRate(), queue == null ? null : queue.stats());
        });
    }

    public StreamingStats snapshotStats(MinecraftServer mcServer) {
        List<PlayerStat> perPlayer = new ArrayList<>();
        for (var entry : trackers.entrySet()) {
            UUID uuid = entry.getKey();
            PlayerLodTracker tracker = entry.getValue();
            String name = uuid.toString();
            if (mcServer != null) {
                ServerPlayer player = mcServer.getPlayerList().getPlayer(uuid);
                if (player != null) name = player.getName().getString();
            }
            perPlayer.add(new PlayerStat(name, tracker.sentCount(),
                    tracker.getLastChunkX(), tracker.getLastChunkZ()));
        }
        return new StreamingStats(
                trackers.size(),
                sectionVersions.size(),
                hashCacheByKey.size(),
                pendingDirtySections.size(),
                queuedDirtySections.size(),
                loadedChunks.size(),
                perPlayer);
    }

    // snapshot player state on the tick thread for async processing
    private record PlayerSnapshot(UUID uuid, int chunkX, int chunkZ,
                                   WorldIdentifier worldId, Identifier dimension,
                                   int minY, int maxY,
                                   Registry<Biome> biomeRegistry) {}

    private void drainPlayerOutbound(ServerPlayer player, PlayerLodTracker tracker) {
        if (player == null || tracker == null) return;
        UUID uuid = player.getUUID();
        Identifier currentDim = player.level().dimension().identifier();

        PlayerOutboundQueue queue = BandwidthThrottler.INSTANCE.getQueue(uuid);
        if (queue == null) return;
        synchronized (queue) {
            synchronized (tracker) {
                while (true) {
                    PlayerOutboundQueue.QueuedPacket packet = queue.peekNext();
                    if (packet == null) break;
                    if (!currentDim.equals(packet.dimension())
                            || !tracker.isBatchSendable(packet.streamEpoch(), packet.batchId())) {
                        queue.pollNext();
                        tracker.completeBatch(packet.streamEpoch(), packet.batchId(), false, currentTick);
                        continue;
                    }
                    if (!BandwidthThrottler.INSTANCE.canSend(uuid, packet.byteSize())) break;
                    queue.pollNext();
                    if (ServerPlayNetworking.canSend(player, packet.payload().type())) {
                        ServerPlayNetworking.send(player, packet.payload());
                        transmittedSections.add(packet.payload().sectionCount());
                    } else tracker.completeBatch(packet.streamEpoch(), packet.batchId(), false, currentTick);
                }
            }
        }
    }

    private void onServerTick(MinecraftServer server) {
        this.server = server;
        currentTick++;
        if (config.debugTrackingEnabled && currentTick % config.debugTrackingInterval == 0) logSyncStats();
        if (!pendingManifestBatches.isEmpty() || !queuedDirtySections.isEmpty()) scheduleStreamWorker();
        flushReadyInitialLoadSections();
        expirePendingDirtySections();
        checkStreamWorkerHealth();

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            BandwidthThrottler.INSTANCE.tick(player.getUUID(), currentTick);
            var tracker = trackers.get(player.getUUID());
            if (tracker != null) {
                tracker.expireManifest(currentTick);
                drainPlayerOutbound(player, tracker);
            }
        }

        if (++tickCounter < tickInterval) return;
        tickCounter = 0;

        List<PlayerSnapshot> snapshots = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            var tracker = trackers.get(player.getUUID());
            if (tracker == null || !tracker.isReady() || !tracker.isProtocolOk() || !tracker.isLodEnabled()) continue;

            if (tracker.hasTimedOutBatch(currentTick, BATCH_ACK_TIMEOUT_TICKS)) {
                sendNewSession(player, tracker, player.level().dimension().identifier());
            }

            tracker.updatePosition(player);
            ServerLevel level = player.level();
            int manifestRadius = tracker.getEffectiveRadius(config.maxClientVoxyRadius) >> 1;
            int manifestX = player.getBlockX() >> 5;
            int manifestZ = player.getBlockZ() >> 5;
            if (tracker.isActiveDimension(level.dimension().identifier())
                    && tracker.needsManifestRefresh(manifestX, manifestZ, manifestRadius, currentTick)) {
                var session = tracker.refreshManifest(currentTick + MANIFEST_TIMEOUT_TICKS,
                        new ManifestScope(manifestX, manifestZ, manifestRadius), dimOrdinals.getOrdinal(level.dimension().identifier()));
                tracker.recordManifestArea(manifestX, manifestZ, manifestRadius, currentTick, config.manifestRefreshIntervalTicks);
                if (ServerPlayNetworking.canSend(player, LODStreamSessionPayload.TYPE)) {
                    ServerPlayNetworking.send(player, new LODStreamSessionPayload(
                            session.dimension(), session.streamEpoch(), session.manifestRequestId(), true,
                            engine.getWorldIdentity(), SemanticSectionHash.VERSION, tracker.manifestScope()));
                }
            }
            WorldIdentifier worldId = WorldIdentifier.of(level);
            if (worldId == null) continue;

            snapshots.add(new PlayerSnapshot(
                    player.getUUID(),
                    tracker.getLastChunkX(),
                    tracker.getLastChunkZ(),
                    worldId,
                    level.dimension().identifier(),
                    level.getMinSectionY() >> 1,
                    (level.getMaxSectionY() >> 1) + 1,
                    level.registryAccess().lookupOrThrow(Registries.BIOME)
            ));
        }

        if (!snapshots.isEmpty()) {
            pendingSnapshotBatch.set(new SnapshotBatch(server, List.copyOf(snapshots)));
            scheduleStreamWorker();
        }
    }

    private void processSnapshots(MinecraftServer server, List<PlayerSnapshot> snapshots) {
        long window = currentTick / config.hashWorkIntervalTicks;
        if (window != lastHashWindow) {
            lastHashWindow = window;
            firstSnapshot = (firstSnapshot + 1) % snapshots.size();
        }
        for (int i = 0; i < snapshots.size(); i++) {
            PlayerSnapshot snap = snapshots.get((firstSnapshot + i) % snapshots.size());
            lastStreamHeartbeat = System.nanoTime();
            drainQueuedDirtySections(server, MAX_DIRTY_SECTIONS_PER_DRAIN / 2);

            var tracker = trackers.get(snap.uuid);
            if (tracker == null || !tracker.isReady()) continue;

            try {
                if (com.dripps.voxyserver.util.ServerStatsTracker.INSTANCE != null) {
                    com.dripps.voxyserver.util.ServerStatsTracker.INSTANCE.markStreamed();
                }
                streamForSnapshot(server, snap, tracker);
            } catch (Exception e) {
                Voxyserver.LOGGER.error("error streaming LODs for player {}", snap.uuid, e);
            }
        }

        drainQueuedDirtySections(server, MAX_DIRTY_SECTIONS_PER_DRAIN / 2);
    }

    public void onDimensionChange(ServerPlayer player, ServerLevel newLevel) {
        var tracker = trackers.get(player.getUUID());
        if (tracker == null || !tracker.isReady()) return;

        tracker.resetScanState();
        Identifier dim = newLevel.dimension().identifier();
        List<PlayerOutboundQueue.QueuedPacket> cleared = BandwidthThrottler.INSTANCE.clearDimension(player.getUUID(), dim);
        for (PlayerOutboundQueue.QueuedPacket pkt : cleared) {
            tracker.completeBatch(pkt.streamEpoch(), pkt.batchId(), false, currentTick);
        }
        if (ServerPlayNetworking.canSend(player, LODClearPayload.TYPE)) {
            ServerPlayNetworking.send(player, LODClearPayload.clearDimension(dim));
        }
        if (tracker.isProtocolOk()) {
            sendNewSession(player, tracker, dim);
        }
    }

    public void clearDimensionForReadyPlayers(ServerLevel level) {
        Identifier dim = level.dimension().identifier();
        RegionHashManager.INSTANCE.clearDimension(dim);
        for (var entry : trackers.entrySet()) {
            PlayerLodTracker tracker = entry.getValue();
            if (tracker == null || !tracker.isReady()) continue;

            ServerPlayer player = level.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player == null || player.level() != level) continue;

            List<PlayerOutboundQueue.QueuedPacket> cleared = BandwidthThrottler.INSTANCE.clearDimension(entry.getKey(), dim);
            for (PlayerOutboundQueue.QueuedPacket pkt : cleared) {
                tracker.completeBatch(pkt.streamEpoch(), pkt.batchId(), false, currentTick);
            }
            tracker.reset();
            if (ServerPlayNetworking.canSend(player, LODClearPayload.TYPE)) {
                ServerPlayNetworking.send(player, LODClearPayload.clearDimension(dim));
            }
            if (tracker.isProtocolOk()) {
                sendNewSession(player, tracker, dim);
            }
        }
    }

    private void streamForSnapshot(MinecraftServer server, PlayerSnapshot snap, PlayerLodTracker tracker) {
        if (corruptedDimensions.contains(snap.dimension)) return;
        if (!tracker.isActiveDimension(snap.dimension)) return;
        if (tracker.isBackedOff(currentTick)) return;
        if (tracker.isManifestGated(snap.dimension, currentTick)) return;
        long streamEpoch = tracker.streamEpoch();
        currentStreamDimension = snap.dimension;
        WorldEngine world = engine.getOrCreate(snap.worldId, snap.dimension);
        if (world == null) return;
        if (!engine.prepareStoredSectionPresence(
                snap.worldId, world, () -> lastStreamHeartbeat = System.nanoTime())) return;

        int playerWorldSecX = snap.chunkX >> 1;
        int playerWorldSecZ = snap.chunkZ >> 1;

        int effectiveRadius = tracker.getEffectiveRadius(config.maxClientVoxyRadius);
        int effectiveMaxSections = Math.min(
                tracker.getEffectiveMaxSections(maxSectionsPerTick),
                tracker.remainingSectionCredit()
        );
        if (effectiveMaxSections <= 0) return;
        int radiusSections = effectiveRadius >> 1;
        Mapper mapper = world.getMapper();
        int dimOrd = dimOrdinals.getOrdinal(snap.dimension);

        if (!tracker.prepareScanWork(
                playerWorldSecX,
                playerWorldSecZ,
                radiusSections,
                snap.minY,
                snap.maxY,
                currentTick,
                IDLE_SCAN_RESTART_TICKS
        )) {
            return;
        }

        List<LODSectionPayload> batch = new ArrayList<>();
        Set<Long> selectedKeys = new HashSet<>();
        Set<Long> pendingKeys = new HashSet<>();
        int sent = 0;
        int candidates = 0;
        int acquireAttempts = 0;
        int missingAcquires = 0;
        int acquireLimit = Math.min(effectiveMaxSections, MAX_ACQUIRE_ATTEMPTS_PER_SNAPSHOT);

        while (sent < effectiveMaxSections
                && candidates < MAX_SCAN_CANDIDATES_PER_SNAPSHOT
                && acquireAttempts < acquireLimit
                && missingAcquires < MAX_MISSING_ACQUIRES_PER_SNAPSHOT) {
            lastStreamHeartbeat = System.nanoTime();

            long key = tracker.pollDeferredDirtySection();
            boolean isDirtySection = (key != PlayerLodTracker.NO_SECTION_KEY);
            boolean releasedPaced = false;
            if (!isDirtySection) {
                key = tracker.pollDeferredPacedSection(currentTick);
                releasedPaced = key != PlayerLodTracker.NO_SECTION_KEY;
            }
            if (key == PlayerLodTracker.NO_SECTION_KEY) {
                key = tracker.nextSectionKeyToScan(currentTick, IDLE_SCAN_RESTART_TICKS);
            }
            if (key == PlayerLodTracker.NO_SECTION_KEY) {
                break;
            }
            candidates++;
            if (selectedKeys.contains(key)) continue;
            if (Math.abs(WorldEngine.getX(key) - playerWorldSecX) > radiusSections
                    || Math.abs(WorldEngine.getZ(key) - playerWorldSecZ) > radiusSections) continue;

            long composite = composeSectionKey(dimOrd, key);
            int version = getSectionVersion(dimOrd, key);
            if (isSectionPendingDirty(dimOrd, key)) {
                pendingKeys.add(key);
                continue;
            }

            // Region summaries are not a proof of complete coverage. Only semantic section
            // fingerprints may suppress delivery until a complete scoped protocol exists.

            // Distance-based pacing check: smooth out network load by pacing mid/far distance
            if (!isDirtySection && !releasedPaced) {
                int secX = WorldEngine.getX(key);
                int secZ = WorldEngine.getZ(key);
                int distSec = Math.max(Math.abs(secX - playerWorldSecX), Math.abs(secZ - playerWorldSecZ));
                if (!BandwidthThrottler.INSTANCE.checkDistancePacing(distSec, candidates, config.enableDistancePacing)) {
                    if (!tracker.deferPacedSection(composite, key, currentTick)) break;
                    continue;
                }
            }

            // we cn js skip without acquiring when the cached hash is current and the client already has it
            long[] cached = hashCacheByKey.get(composite);
            if (cached != null && cached[0] == version
                    && currentTick - cached[2] < config.hashRecheckIntervalTicks
                    && (tracker.hasSent(composite, cached[1]) || tracker.hasOutstanding(composite))) {
                cacheHits.increment(); sendsSkipped.increment(); continue;
            }
            if (!engine.mayHaveStoredSection(snap.worldId, key)) continue;

            SectionSnapshot snapshot = sectionSnapshots.get(composite);
            boolean reusable = snapshot != null && snapshot.version() == version
                    && currentTick - snapshot.tick() < config.hashRecheckIntervalTicks;
            if (!reusable && !hashWorkBudget.tryAcquire(currentTick,
                    config.hashWorkIntervalTicks, config.maxHashComputationsPerInterval,
                    TimeUnit.MILLISECONDS.toNanos(config.maxHashMillisPerInterval))) {
                tracker.deferDirtySection(composite, key);
                break;
            }
            acquireAttempts++;
            WorldSection section = reusable ? null : world.acquireIfExists(key);
            if (!reusable && section == null) {
                missingAcquires++;
                continue;
            }

            long hashStarted = System.nanoTime();
            try {
                LODSectionPayload payload = reusable ? snapshot.payload()
                        : serializeSection(section, snap.dimension, mapper, snap.biomeRegistry);
                // A concurrent edit invalidates this snapshot even if its version has not
                // reached the dirty-drain queue yet.
                if (isSectionPendingDirty(dimOrd, key)
                        || getSectionVersion(dimOrd, key) != version) {
                    tracker.deferDirtySection(composite, key);
                    continue;
                }
                if (reusable) cacheHits.increment(); else hashesComputed.increment();
                if (!reusable) sectionSnapshots.put(composite, new SectionSnapshot(version, currentTick, payload));
                if (payload != null) {
                    long hash = payload.contentHash();
                    hashCacheByKey.put(composite, new long[]{version, hash, reusable ? snapshot.tick() : currentTick});
                    if (!tracker.hasSent(composite, hash) && !tracker.hasOutstanding(composite)) {
                        batch.add(payload);
                        selectedKeys.add(key);
                        sent++;
                    } else sendsSkipped.increment();
                }
            } finally {
                if (!reusable) hashWorkBudget.recordNanos(System.nanoTime() - hashStarted);
                if (section != null) section.release();
            }
        }

        for (long key : pendingKeys) {
            tracker.deferDirtySection(composeSectionKey(dimOrd, key), key);
        }

        if (!batch.isEmpty()) {
            List<LODSectionPayload> toSend = List.copyOf(batch);
            Identifier dim = snap.dimension;
            UUID playerId = snap.uuid;

            // preserialize on stream thread so no heavy encoding on tick thread
            List<PreSerializedLodPayload> packets = new ArrayList<>();
            int packetSections = Math.max(1, Math.min(sectionsPerPacket, PreSerializedLodPayload.MAX_SECTIONS));
            for (int i = 0; i < toSend.size(); i += packetSections) {
                List<LODSectionPayload> chunk = toSend.subList(i, Math.min(toSend.size(), i + packetSections));
                PreSerializedLodPayload encoded = PreSerializedLodPayload.fromBulk(
                        new LODBulkPayload(dim, chunk), server.registryAccess());
                long[] keys = new long[chunk.size()];
                long[] hashes = new long[chunk.size()];
                for (int j = 0; j < chunk.size(); j++) {
                    keys[j] = composeSectionKey(dimOrd, chunk.get(j).sectionKey());
                    hashes[j] = chunk.get(j).contentHash();
                }
                PlayerLodTracker.Delivery delivery = tracker.reserveBatch(
                        dim, streamEpoch, keys, hashes, encoded.data().length, currentTick);
                if (delivery == null) {
                    for (int j = i; j < toSend.size(); j++) {
                        long retryKey = toSend.get(j).sectionKey();
                        tracker.deferDirtySection(composeSectionKey(dimOrd, retryKey), retryKey);
                    }
                    tracker.deferRetry(currentTick);
                    tracker.resetScanState();
                    break;
                }
                PreSerializedLodPayload packet = encoded.withDelivery(delivery.streamEpoch(), delivery.batchId());
                PlayerOutboundQueue.QueuedPacket queuedPacket = new PlayerOutboundQueue.QueuedPacket(
                        packet, dim, delivery.streamEpoch(), delivery.batchId(),
                        chunk.stream().anyMatch(section -> (tracker.hasKnownSection(composeSectionKey(dimOrd, section.sectionKey()))
                                || tracker.isDirtyRecovery(composeSectionKey(dimOrd, section.sectionKey())))),
                        System.nanoTime());
                List<PlayerOutboundQueue.QueuedPacket> evicted = new ArrayList<>();
                boolean enqueued = BandwidthThrottler.INSTANCE.enqueuePacket(playerId, queuedPacket, evicted);
                for (PlayerOutboundQueue.QueuedPacket ev : evicted) {
                    tracker.completeBatch(ev.streamEpoch(), ev.batchId(), false, currentTick);
                }
                if (!enqueued) {
                    tracker.completeBatch(delivery.streamEpoch(), delivery.batchId(), false, currentTick);
                    for (int j = i; j < Math.min(toSend.size(), i + packetSections); j++) {
                        long retryKey = toSend.get(j).sectionKey();
                        tracker.deferDirtySection(composeSectionKey(dimOrd, retryKey), retryKey);
                    }
                    break;
                }
                packets.add(packet);
            }
            List<PreSerializedLodPayload> preEncoded = List.copyOf(packets);

            if (Voxyserver.LOGGER.isDebugEnabled()) {
                int totalBytes = 0;
                for (PreSerializedLodPayload pkt : preEncoded) totalBytes += pkt.data().length;
                Voxyserver.LOGGER.debug("hashsync stream player {} sections {} bytes {}", playerId, toSend.size(), totalBytes);
            }

            server.execute(() -> {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                drainPlayerOutbound(player, tracker);
            });
        }
    }

    private LODSectionPayload serializeSection(WorldSection section, Identifier dimension,
                                                Mapper mapper, Registry<Biome> biomeRegistry) {
        long[] data = section.copyData();

        // build LUT of unique mapping ids
        Long2ShortOpenHashMap lutMap = new Long2ShortOpenHashMap();
        lutMap.defaultReturnValue((short) -1);
        int lutIndex = 0;

        short[] indexArray = new short[data.length];
        for (int i = 0; i < data.length; i++) {
            long id = data[i];
            short idx = lutMap.putIfAbsent(id, (short) lutIndex);
            if (idx == -1) {
                idx = (short) lutIndex++;
            }
            indexArray[i] = idx;
        }

        // convert LUT from voxy mapper ids to vanilla registry ids
        int[] lutBlockStateIds = new int[lutIndex];
        int[] lutBiomeIds = new int[lutIndex];
        byte[] lutLight = new byte[lutIndex];

        for (var entry : lutMap.long2ShortEntrySet()) {
            long mappingId = entry.getLongKey();
            short idx = entry.getShortValue();
            lutBlockStateIds[idx] = IdRemapper.toVanillaBlockStateId(mapper, mappingId);
            lutBiomeIds[idx] = getCachedBiomeId(mapper, mappingId, biomeRegistry);
            lutLight[idx] = (byte) IdRemapper.getLightFromMapping(mappingId);
        }

        long contentHash = SemanticSectionHash.compute(
                SemanticPalette.resolve(lutBlockStateIds, lutBiomeIds, lutLight, biomeRegistry), indexArray);
        if (config.enableRegionHashSync) {
            RegionHashManager.INSTANCE.updateSectionHash(dimension, section.key, contentHash);
        }
        return new LODSectionPayload(dimension, section.key, lutBlockStateIds, lutBiomeIds, lutLight, indexArray, contentHash);
    }

    private int getCachedBiomeId(Mapper mapper, long mappingId, Registry<Biome> biomeRegistry) {
        int biomeId = Mapper.getBiomeId(mappingId);
        int[] cache = biomeIdCaches.get(mapper);
        if (cache != null && biomeId < cache.length && cache[biomeId] != -1) {
            return cache[biomeId];
        }
        int vanillaId = IdRemapper.toVanillaBiomeIdFromMapper(mapper, mappingId, biomeRegistry);
        if (cache == null || biomeId >= cache.length) {
            int newLen = Math.max(biomeId + 1, cache == null ? 16 : cache.length * 2);
            int[] newCache = new int[newLen];
            Arrays.fill(newCache, -1);
            if (cache != null) System.arraycopy(cache, 0, newCache, 0, cache.length);
            cache = newCache;
            biomeIdCaches.put(mapper, cache);
        }
        cache[biomeId] = vanillaId;
        return vanillaId;
    }

    private void onWorldSectionDirty(Identifier dimension, long sectionKey) {
        if (WorldEngine.getLevel(sectionKey) != 0) {
            return;
        }

        RegionHashManager.INSTANCE.invalidateSection(dimension, sectionKey);
        long compositeKey = composeSectionKey(dimOrdinals.getOrdinal(dimension), sectionKey);
        hashCacheByKey.remove(compositeKey);
        sectionSnapshots.remove(compositeKey);
        if (sectionVersions.get(compositeKey) != null) sectionVersions.put(compositeKey, nextRevision());
        if (!pendingDirtySections.containsKey(compositeKey)) {
            return;
        }

        MinecraftServer currentServer = this.server;
        if (currentServer == null) {
            return;
        }

        Long initialLoadDeadline = initialLoadSections.get(compositeKey);
        if (initialLoadDeadline != null && !isInitialLoadReady(compositeKey, initialLoadDeadline)) {
            return;
        }

        try {
            queuedDirtySections.add(compositeKey);
            scheduleStreamWorker();
        } catch (RejectedExecutionException ignored) {
        }
    }

    private void processDirtySection(MinecraftServer server, long compositeKey) {
        if (shouldDeferInitialLoad(compositeKey)) {
            return;
        }

        if (pendingDirtySections.remove(compositeKey) == null) {
            return;
        }

        initialLoadSections.remove(compositeKey);

        Identifier dimension = dimOrdinals.getDimension(extractSectionDimOrdinal(compositeKey));
        long sectionKey = extractSectionKey(compositeKey);

        sectionVersions.compute(compositeKey, (ignored, currentVersion) -> nextRevision());

        ServerLevel level = findLevel(server, dimension);
        if (level == null) return;

        queueDirtySectionForPlayers(dimension, sectionKey);
    }

    private void queueDirtySectionForPlayers(Identifier dimension, long sectionKey) {
        if (corruptedDimensions.contains(dimension)) return;
        int worldSecX = WorldEngine.getX(sectionKey);
        int worldSecZ = WorldEngine.getZ(sectionKey);
        long composite = composeSectionKey(dimOrdinals.getOrdinal(dimension), sectionKey);
        for (PlayerLodTracker tracker : trackers.values()) {
            if (!tracker.isReady() || !tracker.isProtocolOk() || !tracker.isLodEnabled()
                    || !tracker.isActiveDimension(dimension)) continue;
            int radius = tracker.getEffectiveRadius(config.maxClientVoxyRadius) >> 1;
            if (Math.abs(worldSecX - (tracker.getLastChunkX() >> 1)) > radius
                    || Math.abs(worldSecZ - (tracker.getLastChunkZ() >> 1)) > radius) continue;
            tracker.deferDirtySection(composite, sectionKey, currentTick);
        }
    }

    private boolean isSectionPendingDirty(int dimOrdinal, long sectionKey) {
        Long blockUntilTick = pendingDirtySections.get(composeSectionKey(dimOrdinal, sectionKey));
        return blockUntilTick != null && blockUntilTick > currentTick;
    }

    private int nextRevision() {
        return nextSectionRevision.updateAndGet(value -> value == Integer.MAX_VALUE ? 1 : value + 1);
    }

    private int getSectionVersion(int dimOrdinal, long sectionKey) {
        return sectionVersions.compute(composeSectionKey(dimOrdinal, sectionKey),
                (ignored, version) -> version == null ? nextRevision() : version);
    }

    private void expirePendingDirtySections() {
        if (pendingDirtySections.isEmpty()) {
            return;
        }

        for (var entry : pendingDirtySections.entrySet()) {
            if (entry.getValue() > currentTick) {
                continue;
            }

            if (!pendingDirtySections.remove(entry.getKey(), entry.getValue())) {
                continue;
            }

            initialLoadSections.remove(entry.getKey());

            sectionVersions.compute(entry.getKey(), (ignored, currentVersion) -> nextRevision());
        }
    }

    private static ServerLevel findLevel(MinecraftServer server, Identifier dimension) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().identifier().equals(dimension)) {
                return level;
            }
        }
        return null;
    }

    private void flushReadyInitialLoadSections() {
        if (initialLoadSections.isEmpty() || pendingDirtySections.isEmpty()) {
            return;
        }

        MinecraftServer currentServer = this.server;
        if (currentServer == null) {
            return;
        }

        for (var entry : initialLoadSections.entrySet()) {
            Long compositeKey = entry.getKey();
            long deadline = entry.getValue();
            int dimOrd = extractSectionDimOrdinal(compositeKey);
            long sectionKey = extractSectionKey(compositeKey);
            int loadedChunkCount = loadedChunkCountForSection(dimOrd, sectionKey);
            boolean readyByFootprint = loadedChunkCount == 4;
            boolean readyByDeadline = currentTick >= deadline && loadedChunkCount >= INITIAL_LOAD_MIN_CHUNKS_AT_DEADLINE;
            if (!readyByDeadline && !readyByFootprint) {
                continue;
            }

            if (!initialLoadSections.remove(compositeKey, deadline)) {
                continue;
            }

            // remove pending gate immediately so the snapshot scan can reach this section
            // without waiting for the dirty queue (which may be backed up behind thousands
            // of dirty callbacks and cause the pending entry to expire before processing)
            if (pendingDirtySections.remove(compositeKey) == null) {
                continue;
            }

            // bump version so hasSent() returns false and the scanner resends
            sectionVersions.compute(compositeKey, (ignored, currentVersion) -> nextRevision());
        }
    }

    private void scheduleStreamWorker() {
        if (!streamWorkerScheduled.compareAndSet(false, true)) {
            return;
        }

        try {
            streamExecutor.execute(this::runStreamWorker);
        } catch (RejectedExecutionException ignored) {
            streamWorkerScheduled.set(false);
        }
    }

    private void runStreamWorker() {
        try {
            if (Thread.currentThread().isInterrupted()) return;
            lastStreamHeartbeat = System.nanoTime();
            drainManifestBatches();
            drainQueuedDirtySections(server, MAX_DIRTY_SECTIONS_PER_DRAIN);
            SnapshotBatch batch = pendingSnapshotBatch.getAndSet(null);
            if (batch != null) processSnapshots(batch.server(), batch.snapshots());
        } finally {
            lastStreamHeartbeat = System.nanoTime();
            currentStreamDimension = null;
            streamWorkerScheduled.set(false);
            if (pendingSnapshotBatch.get() != null) scheduleStreamWorker();
        }
    }

    private static ExecutorService createStreamExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "VoxyServer Streaming");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
    }

    private void checkStreamWorkerHealth() {
        if (!streamWorkerScheduled.get()) {
            lastStreamHeartbeat = System.nanoTime();
            return;
        }
        long elapsed = System.nanoTime() - lastStreamHeartbeat;
        if (elapsed > STREAM_WORKER_STUCK_NANOS) {
            Identifier dim = currentStreamDimension;
            if (dim != null) {
                handleVoxyCorruption(dim, "stuck stream worker (no heartbeat for "
                        + TimeUnit.NANOSECONDS.toSeconds(elapsed) + "s)", null);
            }
            Voxyserver.LOGGER.error(
                    "[VoxyServer] stream worker unresponsive for {}s, prolly blocked on a leaked Voxy StampedLock. "
                            + "replacing stream executor. a daemon thread has been leaked.",
                    TimeUnit.NANOSECONDS.toSeconds(elapsed));

            ExecutorService oldExecutor = streamExecutor;
            streamExecutor = createStreamExecutor();
            lastStreamHeartbeat = System.nanoTime();
            currentStreamDimension = null;
            streamWorkerScheduled.set(false);
            oldExecutor.shutdownNow();

            if (!pendingManifestBatches.isEmpty()
                    || !queuedDirtySections.isEmpty() || pendingSnapshotBatch.get() != null) {
                scheduleStreamWorker();
            }
        }
    }

    private int drainManifestBatches() {
        if (server == null || (lastManifestDrainTick != Long.MIN_VALUE
                && currentTick - lastManifestDrainTick < 5)) return 0;
        ManifestBatch batch = pendingManifestBatches.poll();
        if (batch == null) return 0;
        lastManifestDrainTick = currentTick;
        try {
            PlayerLodTracker tracker = trackers.get(batch.playerId());
            if (tracker == null || !tracker.matchesManifest(batch.dimension(), batch.streamEpoch(), batch.requestId())) return 1;
            int dimOrd = dimOrdinals.getOrdinal(batch.dimension());
            int acknowledgement = tracker.acceptManifestPage(batch.dimension(), batch.streamEpoch(), batch.requestId(),
                    dimOrd, batch.keys(), batch.hashes(), batch.status(), batch.pageId(), currentTick, hashSyncEnabled);
            server.execute(() -> {
                ServerPlayer player = server.getPlayerList().getPlayer(batch.playerId());
                if (player != null && tracker.matchesManifest(batch.dimension(), batch.streamEpoch(), batch.requestId())) {
                    ServerPlayNetworking.send(player, new LODManifestAckPayload(batch.dimension(), batch.streamEpoch(),
                            batch.requestId(), batch.pageId(), acknowledgement));
                }
            });
        } finally {
            pendingManifestPlayers.remove(batch.playerId());
        }
        return 1;
    }

    private void handleVoxyCorruption(Identifier dimension, String context, Exception e) {
        if (!corruptedDimensions.add(dimension)) return;
        if (e != null) {
            Voxyserver.LOGGER.error(
                    "[VoxyServer] Voxy state corruption :/ ({}) for dimension '{}'. "
                            + "lod streaming disabled for this dimension until server restart.",
                    context, dimension, e);
        } else {
            Voxyserver.LOGGER.error(
                    "[VoxyServer] Voxy state corruption :/ ({}) for dimension '{}'. "
                            + "lod streaming disabled for this dimension until server restart.",
                    context, dimension);
        }
    }

    private int drainQueuedDirtySections(MinecraftServer server, int maxSections) {
        if (server == null || queuedDirtySections.isEmpty()) {
            return 0;
        }

        int drained = 0;
        while (!queuedDirtySections.isEmpty() && drained < maxSections) {
            Iterator<Long> iterator = queuedDirtySections.iterator();
            if (!iterator.hasNext()) {
                return drained;
            }

            Long compositeKey = iterator.next();
            if (!queuedDirtySections.remove(compositeKey)) {
                continue;
            }

            processDirtySection(server, compositeKey);
            drained++;
        }
        return drained;
    }

    private boolean shouldDeferInitialLoad(long compositeKey) {
        Long deadline = initialLoadSections.get(compositeKey);
        if (deadline == null) {
            return false;
        }

        int dimOrd = extractSectionDimOrdinal(compositeKey);
        long sectionKey = extractSectionKey(compositeKey);
        int loadedChunkCount = loadedChunkCountForSection(dimOrd, sectionKey);
        if (isInitialLoadReady(deadline, loadedChunkCount)) {
            return false;
        }

        return true;
    }

    private boolean isInitialLoadReady(long compositeKey, long deadline) {
        int dimOrd = extractSectionDimOrdinal(compositeKey);
        long sectionKey = extractSectionKey(compositeKey);
        return isInitialLoadReady(deadline, loadedChunkCountForSection(dimOrd, sectionKey));
    }

    private boolean isInitialLoadReady(long deadline, int loadedChunkCount) {
        return loadedChunkCount == 4
                || (currentTick >= deadline && loadedChunkCount >= INITIAL_LOAD_MIN_CHUNKS_AT_DEADLINE);
    }

    private int loadedChunkCountForSection(int dimOrdinal, long sectionKey) {
        int baseChunkX = WorldEngine.getX(sectionKey) << 1;
        int baseChunkZ = WorldEngine.getZ(sectionKey) << 1;
        int loaded = 0;
        if (loadedChunks.contains(composeChunkKey(dimOrdinal, baseChunkX, baseChunkZ))) loaded++;
        if (loadedChunks.contains(composeChunkKey(dimOrdinal, baseChunkX + 1, baseChunkZ))) loaded++;
        if (loadedChunks.contains(composeChunkKey(dimOrdinal, baseChunkX, baseChunkZ + 1))) loaded++;
        if (loadedChunks.contains(composeChunkKey(dimOrdinal, baseChunkX + 1, baseChunkZ + 1))) loaded++;
        return loaded;
    }
}
