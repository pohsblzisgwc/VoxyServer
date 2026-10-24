package com.dripps.voxyserver.server;

import com.dripps.voxyserver.Voxyserver;
import com.dripps.voxyserver.config.VoxyServerConfig;
import com.dripps.voxyserver.network.*;
import com.dripps.voxyserver.server.throttle.*;
import com.dripps.voxyserver.sync.SectionDataAccess;
import com.dripps.voxyserver.sync.version.*;
import com.dripps.voxyserver.util.*;

import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.WorldIdentifier;

import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Full-range, client-led version reconciliation. Only final LOD0 delivery uses rate budgets. */
public final class LodStreamingService {
    private final ServerLodEngine engine;
    private volatile VoxyServerConfig config;
    private volatile MinecraftServer server;
    private volatile boolean closed;
    private long tick, lastCheckpoint;
    private boolean resumed;
    private final ConcurrentHashMap<UUID, State> players = new ConcurrentHashMap<>();
    private final ScheduledExecutorService worker =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        var t = new Thread(r, "VoxyServer Maintenance");
                        t.setDaemon(true);
                        t.setPriority(Thread.MIN_PRIORITY);
                        return t;
                    });
    private final MaintenanceBudget workBudget = new MaintenanceBudget();
    private final CoalescedWakeup wake = new CoalescedWakeup(worker, this::cycle, () -> closed);
    private final AtomicBoolean dirtyWakeScheduled = new AtomicBoolean();
    private final SourceMaintenance source;
    private long sentNodes,
            roundsCompleted,
            roundsFailed,
            busyRejected,
            ackTimeouts,
            workerNanos,
            cycles,
            maxQuantum;
    private int playerTurn;

    private static final class State {
        final UUID id;
        final ArrayDeque<net.minecraft.network.protocol.common.custom.CustomPacketPayload>
                controls = new ArrayDeque<>();
        Identifier dimension;
        java.util.UUID authority;
        final RoundAdmission admission = new RoundAdmission();
        long lastCompletedRound;
        int lastCompletedSelectionPage;
        long epoch, batchSequence, sent, failedBatches, retries, requests, scopeRejected;
        boolean ready, protocolOk, enabled = true;
        int radius, maxSections;
        Round round;

        State(UUID id) {
            this.id = id;
        }
    }

    private static final class Round {
        final long id;
        final Identifier dimension;
        final VersionScope scope;
        final ServerLevel level;
        final WorldIdentifier world;
        final SummaryAssembly summary;
        final BitSet offered = new BitSet();
        long lastActivity = System.nanoTime(), retryAt;
        int rootCursor,
                changedRoots,
                tableOffset,
                selectionPages,
                selectionPage = -1,
                lastCompletedPage = -1,
                attempts;
        boolean tablesCompleteQueued;
        int[] tableValues;
        long[] tableKeys;
        int[] tableVersions;
        long previousLastKey = -1;
        long tableRoot, tableDigest;
        long[] selectionKeys;
        int[] selectionVersions;
        final BitSet completed = new BitSet();
        Flight flight;

        Round(long id, VersionScope scope, ServerLevel level) {
            this.id = id;
            dimension = level.dimension().identifier();
            this.scope = scope;
            summary = new SummaryAssembly(scope);
            this.level = level;
            world = WorldIdentifier.of(level);
        }
    }

    private static final class Flight {
        final long id;
        final int[] positions;
        final PlayerOutboundQueue.QueuedPacket packet;
        final BatchReceipt receipt;

        Flight(long id, int[] positions, PlayerOutboundQueue.QueuedPacket packet) {
            this.id = id;
            this.positions = positions;
            this.packet = packet;
            receipt = new BatchReceipt(packet.streamEpoch(), id);
        }
    }

    public LodStreamingService(ServerLodEngine engine, VoxyServerConfig config) {
        this.engine = engine;
        this.config = config;
        source =
                new SourceMaintenance(
                        engine,
                        engine.maintenanceJournal(),
                        config.maintenanceQuietMillis,
                        config.maintenanceCooldownMillis,
                        wake::request);
        source.dirty.updateTiming(
                config.maintenanceQuietMillis * 1_000_000L,
                config.maintenanceCooldownMillis * 1_000_000L,
                config.maintenanceCooldownJitterMillis * 1_000_000L);
    }

    public static void registerHandlers(java.util.function.Supplier<LodStreamingService> active) {
        ServerPlayConnectionEvents.JOIN.register(
                (h, s, server) -> {
                    var a = active.get();
                    if (a != null) a.onJoin(h.getPlayer());
                });
        ServerPlayConnectionEvents.DISCONNECT.register(
                (h, server) -> {
                    var a = active.get();
                    if (a != null) a.onDisconnect(h.getPlayer());
                });
        ServerPlayNetworking.registerGlobalReceiver(
                LODHandshakePayload.TYPE,
                (p, c) -> {
                    var a = active.get();
                    if (a != null) a.handshake(c.player(), p);
                    else if (ServerPlayNetworking.canSend(c.player(), LODProtocolPayload.TYPE))
                        ServerPlayNetworking.send(c.player(), new LODProtocolPayload(0));
                });
        ServerPlayNetworking.registerGlobalReceiver(
                LODPreferencesPayload.TYPE,
                (p, c) -> {
                    var a = active.get();
                    if (a != null) a.preferences(c.player(), p);
                });
        ServerPlayNetworking.registerGlobalReceiver(
                VersionCancelPayload.TYPE,
                (p, c) -> {
                    var a = active.get();
                    if (a != null) {
                        var s = a.players.get(c.player().getUUID());
                        if (a.current(s, p.epoch()))
                            synchronized (s) {
                                if (s.round != null && s.round.id == p.round()) a.fail(s, s.round);
                            }
                    }
                });
        ServerPlayNetworking.registerGlobalReceiver(
                VersionBeginPayload.TYPE,
                (p, c) -> {
                    var a = active.get();
                    if (a != null) a.begin(c.player(), p);
                });
        ServerPlayNetworking.registerGlobalReceiver(
                VersionSummaryPayload.TYPE,
                (p, c) -> {
                    var a = active.get();
                    if (a != null) a.receiveSummary(c.player(), p);
                });
        ServerPlayNetworking.registerGlobalReceiver(
                VersionSelectPayload.TYPE,
                (p, c) -> {
                    var a = active.get();
                    if (a != null) a.receiveSelection(c.player(), p);
                });
        ServerPlayNetworking.registerGlobalReceiver(
                LODBatchResultPayload.TYPE,
                (p, c) -> {
                    var a = active.get();
                    if (a != null) a.result(c.player(), p);
                });
    }

    public void start(MinecraftServer server) {
        this.server = server;
        for (var p : server.getPlayerList().getPlayers()) {
            onJoin(p);
            if (ServerPlayNetworking.canSend(p, LODProtocolPayload.TYPE))
                ServerPlayNetworking.send(
                        p, new LODProtocolPayload(VoxyServerNetworking.PROTOCOL_VERSION));
        }
        startWorker();
    }

    void startWorker() {
        worker.scheduleWithFixedDelay(this::cycle, 50, 50, TimeUnit.MILLISECONDS);
    }

    private void onJoin(ServerPlayer p) {
        players.put(p.getUUID(), new State(p.getUUID()));
        BandwidthThrottler.INSTANCE.registerPlayer(p.getUUID(), config.maxBandwidthBytesPerSec);
    }

    private void onDisconnect(ServerPlayer p) {
        var s = players.remove(p.getUUID());
        if (s != null) clear(s);
        BandwidthThrottler.INSTANCE.unregisterPlayer(p.getUUID());
    }

    private void handshake(ServerPlayer p, LODHandshakePayload h) {
        var s = players.get(p.getUUID());
        if (s == null) return;
        s.ready = true;
        s.protocolOk = h.protocol() == VoxyServerNetworking.PROTOCOL_VERSION;
        ServerPlayNetworking.send(p, new LODProtocolPayload(VoxyServerNetworking.PROTOCOL_VERSION));
        if (s.protocolOk) {
            settings(p);
            session(p, s);
        }
    }

    private void preferences(ServerPlayer p, LODPreferencesPayload q) {
        var s = players.get(p.getUUID());
        if (s == null) return;
        synchronized (s) {
            boolean wasEnabled = s.enabled;
            s.enabled = q.enabled();
            s.radius = q.lodStreamRadius();
            s.maxSections = q.maxSectionsPerTick();
            if (!s.enabled) clear(s);
            else if (!wasEnabled && s.ready && s.protocolOk) session(p, s);
            // Range changes affect the next grant. Repeated preferences never replace a transfer.
        }
    }

    private void settings(ServerPlayer p) {
        ServerPlayNetworking.send(
                p,
                new LODServerSettingsPayload(
                        config.maxClientVoxyRadius, config.maxSectionsPerTickPerPlayer));
    }

    private void session(ServerPlayer p, State s) {
        synchronized (s) {
            clear(s);
            s.dimension = p.level().dimension().identifier();
            s.epoch = SessionEpoch.next();
            s.authority = engine.versions().namespace();
            s.admission.reset();
            s.lastCompletedRound = 0;
            ServerPlayNetworking.send(
                    p,
                    new VersionSessionPayload(
                            s.dimension,
                            s.epoch,
                            engine.getWorldIdentity(),
                            engine.versions().namespace()));
        }
    }

    private boolean current(State s, long epoch) {
        return s != null && s.ready && s.protocolOk && s.enabled && s.epoch == epoch && !closed;
    }

    private void status(State s, long round, int status, int page, int count) {
        // Per-player metadata is bounded independently of the terrain byte/token queue.
        if (s.controls.size() < 16)
            s.controls.add(new VersionStatusPayload(s.epoch, round, status, page, count));
    }

    private void begin(ServerPlayer player, VersionBeginPayload p) {
        State s = players.get(player.getUUID());
        if (!current(s, p.epoch())) return;
        synchronized (s) {
            s.requests++;
            var admission = s.admission.begin(p.round());
            if (admission == RoundAdmission.Result.BUSY) {
                busyRejected++;
                status(s, p.round(), VersionStatusPayload.BUSY, 0, 0);
                return;
            }
            if (admission == RoundAdmission.Result.STALE) {
                status(s, p.round(), VersionStatusPayload.STALE, 0, 0);
                return;
            }
            if (admission == RoundAdmission.Result.NEW) {
                int cap =
                        s.radius <= 0
                                ? config.maxClientVoxyRadius
                                : Math.min(s.radius, config.maxClientVoxyRadius);
                var level = player.level();
                var scope =
                        new VersionScope(
                                player.getBlockX(),
                                player.getBlockZ(),
                                Math.min(p.radiusChunks(), cap),
                                level.getMinY(),
                                level.getMinY() + level.getHeight(),
                                Math.max(
                                        level.getMinY(),
                                        Math.min(level.getMaxY(), player.getBlockY())));
                s.round = new Round(p.round(), scope, level);
            }
            if (s.round != null && s.controls.size() < 16)
                s.controls.add(
                        new VersionScopePayload(
                                s.epoch, p.round(), s.round.dimension, s.round.scope));
        }
    }

    private void receiveSummary(ServerPlayer player, VersionSummaryPayload p) {
        State s = players.get(player.getUUID());
        if (!current(s, p.epoch())) return;
        synchronized (s) {
            Round r = s.round;
            if (r == null || r.id != p.round()) {
                status(
                        s,
                        p.round(),
                        r == null ? VersionStatusPayload.STALE : VersionStatusPayload.BUSY,
                        0,
                        0);
                return;
            }
            if (!r.scope.equals(p.scope()) || !r.dimension.equals(p.dimension())) {
                s.scopeRejected++;
                fail(s, r);
                return;
            }
            try {
                r.summary.add(p.page(), p.pages(), p.digests());
                r.lastActivity = System.nanoTime();
            } catch (IllegalArgumentException e) {
                fail(s, r);
                return;
            }
        }
        wake.request();
    }

    private void receiveSelection(ServerPlayer player, VersionSelectPayload p) {
        State s = players.get(player.getUUID());
        if (!current(s, p.epoch())) return;
        synchronized (s) {
            Round r = s.round;
            if (r == null && p.round() == s.lastCompletedRound) {
                status(s, p.round(), VersionStatusPayload.DONE, s.lastCompletedSelectionPage, 0);
                return;
            }
            if (r == null || r.id != p.round()) {
                status(
                        s,
                        p.round(),
                        r == null ? VersionStatusPayload.STALE : VersionStatusPayload.BUSY,
                        0,
                        0);
                return;
            }
            if (!r.tablesCompleteQueued) {
                fail(s, r);
                return;
            }
            if (p.page() == r.lastCompletedPage) {
                status(s, r.id, VersionStatusPayload.PAGE_DONE, p.page(), 0);
                return;
            }
            if (r.selectionKeys != null) {
                if (p.page() != r.selectionPage
                        || p.pages() != r.selectionPages
                        || !Arrays.equals(p.keys(), r.selectionKeys)
                        || !Arrays.equals(p.versions(), r.selectionVersions)) fail(s, r);
                return;
            }
            if (p.page() != r.lastCompletedPage + 1
                    || (r.selectionPages != 0 && p.pages() != r.selectionPages)) {
                fail(s, r);
                return;
            }
            if (p.keys().length > 0
                    && r.lastCompletedPage >= 0
                    && r.scope.compareDelivery(p.keys()[0], r.previousLastKey) <= 0) {
                fail(s, r);
                return;
            }
            for (int i = 1; i < p.keys().length; i++)
                if (r.scope.compareDelivery(p.keys()[i - 1], p.keys()[i]) >= 0) {
                    fail(s, r);
                    return;
                }
            for (long key : p.keys())
                if (!r.scope.contains(key)
                        || !r.offered.get(r.scope.rootIndex(VersionScope.root(key)))) {
                    fail(s, r);
                    return;
                }
            r.selectionPages = p.pages();
            r.selectionPage = p.page();
            r.selectionKeys = p.keys().clone();
            r.selectionVersions = p.versions().clone();
            r.completed.clear();
            r.lastActivity = System.nanoTime();
            if (p.keys().length == 0) completePage(s, r);
        }
        wake.request();
    }

    private void result(ServerPlayer p, LODBatchResultPayload result) {
        State s = players.get(p.getUUID());
        if (!current(s, result.streamEpoch())) return;
        synchronized (s) {
            Round r = s.round;
            if (r == null
                    || r.flight == null
                    || r.flight.id != result.batchId()
                    || !r.flight.receipt.acknowledge(result.streamEpoch(), result.batchId()))
                return;
            Flight f = r.flight;
            r.flight = null;
            r.lastActivity = System.nanoTime();
            if (result.status() == LODBatchResultPayload.SUCCESS) {
                for (int i : f.positions) r.completed.set(i);
                r.attempts = 0;
                if (r.completed.cardinality() == r.selectionKeys.length) completePage(s, r);
            } else retry(s, r);
        }
        wake.request();
    }

    private void completePage(State s, Round r) {
        r.lastCompletedPage = r.selectionPage;
        if (r.selectionKeys.length > 0)
            r.previousLastKey = r.selectionKeys[r.selectionKeys.length - 1];
        r.selectionKeys = null;
        r.selectionVersions = null;
        r.completed.clear();
        if (r.lastCompletedPage + 1 == r.selectionPages) {
            status(s, r.id, VersionStatusPayload.DONE, r.lastCompletedPage, 0);
            s.admission.finish(r.id);
            s.lastCompletedRound = r.id;
            s.lastCompletedSelectionPage = r.lastCompletedPage;
            s.round = null;
            roundsCompleted++;
        } else status(s, r.id, VersionStatusPayload.PAGE_DONE, r.lastCompletedPage, 0);
    }

    private void retry(State s, Round r) {
        s.failedBatches++;
        s.retries++;
        r.attempts++;
        r.retryAt = System.nanoTime() + 5_000_000_000L;
        if (r.attempts >= 8) fail(s, r);
    }

    private void fail(State s, Round r) {
        if (s.round != r) return;
        BandwidthThrottler.INSTANCE.clearPlayerQueue(s.id);
        r.flight = null;
        s.admission.finish(r.id);
        s.round = null;
        roundsFailed++;
        status(s, r.id, VersionStatusPayload.FAILED, Math.max(0, r.selectionPage), 0);
    }

    private void clear(State s) {
        synchronized (s) {
            if (s.round != null) s.admission.finish(s.round.id);
            s.round = null;
            s.controls.clear();
            BandwidthThrottler.INSTANCE.clearPlayerQueue(s.id);
        }
    }

    /** Emits at most one bounded metadata page, without terrain bandwidth/rate throttling. */
    private boolean metadata(State s) {
        Round r;
        synchronized (s) {
            r = s.round;
            if (r == null
                    || !r.summary.complete()
                    || r.tablesCompleteQueued
                    || s.controls.size() >= 16) return false;
        }
        // Never hold a player monitor during inventory/storage access: the tick thread uses it.
        if (engine.getOrCreate(r.world, r.dimension) == null)
            throw new IllegalStateException("LOD world unavailable");
        if (r.tableValues == null) {
            int checks = 0;
            while (r.rootCursor < r.scope.rootCount() && checks++ < 64) {
                int index = r.rootCursor++;
                long root = r.scope.rootAt(index);
                long digest = engine.versions().digest(r.dimension, root, r.scope);
                if (digest == r.summary.digest(index)) continue;
                r.offered.set(index);
                r.changedRoots++;
                r.tableRoot = root;
                r.tableValues = engine.versions().snapshot(r.dimension, root, r.scope);
                r.tableDigest = VersionDigest.compute(r.tableValues, null, root);
                r.tableOffset = 0;
                int count = 0;
                for (int v : r.tableValues) if (v > 0) count++;
                r.tableKeys = new long[count];
                r.tableVersions = new int[count];
                int n = 0;
                for (int i = 0; i < 4096; i++)
                    if (r.tableValues[i] > 0) {
                        r.tableKeys[n] = VersionScope.node(root, i);
                        r.tableVersions[n++] = r.tableValues[i];
                    }
                break;
            }
            if (r.tableValues == null) {
                synchronized (s) {
                    if (s.round != r) return false;
                    if (r.rootCursor == r.scope.rootCount()) {
                        r.tablesCompleteQueued = true;
                        status(s, r.id, VersionStatusPayload.TABLES_COMPLETE, 0, r.changedRoots);
                    }
                }
                return true;
            }
        }
        int total = Math.max(1, (r.tableKeys.length + 255) / 256),
                page = r.tableOffset / 256,
                end = Math.min(r.tableOffset + 256, r.tableKeys.length);
        var payload =
                new VersionTablePayload(
                        s.epoch,
                        r.id,
                        r.tableRoot,
                        r.tableDigest,
                        page,
                        total,
                        Arrays.copyOfRange(r.tableKeys, r.tableOffset, end),
                        Arrays.copyOfRange(r.tableVersions, r.tableOffset, end));
        synchronized (s) {
            if (s.round != r || s.controls.size() >= 16) return false;
            s.controls.add(payload);
            r.tableOffset = end;
            r.lastActivity = System.nanoTime();
            if (page + 1 == total) {
                r.tableValues = null;
                r.tableKeys = null;
                r.tableVersions = null;
            }
        }
        return true;
    }

    /** One in-flight batch per player; selected pages hold at most 256 node IDs. */
    private boolean deliver(State s) {
        Round r;
        long epoch;
        Identifier dimension;
        int[] positions;
        long[] keys;
        synchronized (s) {
            r = s.round;
            if (r == null
                    || r.selectionKeys == null
                    || r.flight != null
                    || System.nanoTime() < r.retryAt) return false;
            int limit =
                    Math.min(
                            16,
                            Math.min(
                                    config.sectionsPerPacket,
                                    s.maxSections <= 0
                                            ? config.maxSectionsPerTickPerPlayer
                                            : Math.min(
                                                    s.maxSections,
                                                    config.maxSectionsPerTickPerPlayer)));
            var pos = new ArrayList<Integer>();
            for (int i = r.completed.nextClearBit(0);
                    i < r.selectionKeys.length && pos.size() < limit;
                    i = r.completed.nextClearBit(i + 1)) pos.add(i);
            if (pos.isEmpty()) {
                completePage(s, r);
                return true;
            }
            positions = pos.stream().mapToInt(Integer::intValue).toArray();
            keys = new long[positions.length];
            for (int i = 0; i < keys.length; i++) keys[i] = r.selectionKeys[positions[i]];
            epoch = s.epoch;
            dimension = s.dimension;
        }
        WorldEngine world = engine.getOrCreate(r.world, dimension);
        if (world == null) throw new IllegalStateException("LOD world unavailable");
        world.acquireRef();
        var nodes = new ArrayList<VersionedNodePayload>();
        try {
            for (long key : keys) {
                synchronized (world) {
                    var section = SectionDataAccess.acquireStored(world, key);
                    if (section == null)
                        throw new IllegalStateException("selected LOD0 node unavailable: " + key);
                    try {
                        int version = engine.versions().get(dimension, key);
                        if (version < 1) throw new IllegalStateException("missing LOD0 version");
                        nodes.add(
                                VersionedNodePayload.encode(
                                        key,
                                        version,
                                        section.copyData(),
                                        section.getNonEmptyChildren(),
                                        world.getMapper(),
                                        r.level.registryAccess().lookupOrThrow(Registries.BIOME)));
                    } finally {
                        section.release();
                    }
                }
            }
        } finally {
            world.releaseRef();
        }
        byte[] data = VersionedNodePayload.encodeBatch(nodes, server.registryAccess());
        synchronized (s) {
            if (s.round != r || s.epoch != epoch || r.flight != null) return true;
            long batch = ++s.batchSequence;
            var raw = new PreSerializedLodPayload(epoch, batch, nodes.size(), data, 0, r.id);
            var packet =
                    new PlayerOutboundQueue.QueuedPacket(
                            raw, dimension, epoch, batch, false, System.nanoTime());
            r.flight = new Flight(batch, positions, packet);
            var evicted = new ArrayList<PlayerOutboundQueue.QueuedPacket>();
            boolean accepted = BandwidthThrottler.INSTANCE.enqueuePacket(s.id, packet, evicted);
            if (!accepted || !evicted.isEmpty()) {
                BandwidthThrottler.INSTANCE.clearPlayerQueue(s.id);
                r.flight = null;
                retry(s, r);
            }
        }
        return true;
    }

    private void cycle() {
        if (closed || server == null) return;
        long start = System.nanoTime();
        try {
            List<State> states = new ArrayList<>(players.values());
            // Fair bounded work scheduling, not a per-second control-message rate limiter.
            long controlStart = System.nanoTime();
            int turns = 0;
            while (!states.isEmpty()
                    && turns++ < 64
                    && System.nanoTime() - controlStart < 5_000_000L) {
                State s = states.get(Math.floorMod(playerTurn++, states.size()));
                boolean did = metadata(s);
                if (!did && turns >= states.size()) break;
            }
            long now = System.nanoTime();
            while (!closed
                    && now - start < 50_000_000L
                    && workBudget.tryAcquire(
                            now / 50_000_000L,
                            20,
                            Integer.MAX_VALUE,
                            TimeUnit.MILLISECONDS.toNanos(config.maintenanceMillisPerSecond))) {
                long began = System.nanoTime();
                boolean did = source.step(server);
                if (!states.isEmpty()) {
                    State s = states.get(Math.floorMod(playerTurn++, states.size()));
                    try {
                        did = deliver(s) || did;
                    } catch (Exception e) {
                        synchronized (s) {
                            if (s.round != null) retry(s, s.round);
                        }
                        Voxyserver.LOGGER.warn("LOD0 delivery deferred: {}", e.toString());
                    }
                }
                long elapsed = System.nanoTime() - began;
                maxQuantum = Math.max(maxQuantum, elapsed);
                workBudget.recordNanos(elapsed);
                now = System.nanoTime();
                if (!did) break;
            }
            if (now - lastCheckpoint >= 30_000_000_000L) {
                lastCheckpoint = now;
                source.checkpoint();
                engine.versions().save();
            }
        } catch (Throwable e) {
            Voxyserver.LOGGER.error("version sync worker failed", e);
        } finally {
            cycles++;
            workerNanos += System.nanoTime() - start;
            scheduleDirtyWake();
        }
    }

    /**
     * Queue one delayed worker turn while source work remains. Dirty work may be waiting for its
     * quiet window, retry cooldown, or the next maintenance-budget window; none of those waits
     * should depend on another client packet arriving.
     */
    private void scheduleDirtyWake() {
        if (closed || server == null || !source.hasPendingWork()) return;
        if (!dirtyWakeScheduled.compareAndSet(false, true)) return;
        try {
            worker.schedule(
                    () -> {
                        dirtyWakeScheduled.set(false);
                        if (!closed && source.hasPendingWork()) wake.request();
                    },
                    1,
                    TimeUnit.SECONDS);
        } catch (RejectedExecutionException shuttingDown) {
            dirtyWakeScheduled.set(false);
        }
    }

    private void requestMaintenance() {
        if (closed) return;
        wake.request();
        scheduleDirtyWake();
    }

    public void onTick(MinecraftServer server) {
        if (closed) return;
        boolean firstServerTick = this.server == null;
        this.server = server;
        tick++;
        if (firstServerTick) requestMaintenance();
        if (!resumed) {
            resumed = true;
            Map<String, ServerLevel> levels = new HashMap<>();
            for (var l : server.getAllLevels())
                levels.put(l.dimension().identifier().toString(), l);
            worker.execute(
                    () -> {
                        source.resume(levels);
                        requestMaintenance();
                    });
        }
        for (State s : players.values()) {
            var p = server.getPlayerList().getPlayer(s.id);
            if (p == null) continue;
            BandwidthThrottler.INSTANCE.tick(s.id, tick);
            if (s.protocolOk && !engine.versions().namespace().equals(s.authority)) session(p, s);
            synchronized (s) {
                while (!s.controls.isEmpty())
                    ServerPlayNetworking.send(p, s.controls.removeFirst());
                Round r = s.round;
                if (r != null && r.flight != null && r.flight.receipt.expire(tick, 600)) {
                    ackTimeouts++;
                    r.flight = null;
                    retry(s, r);
                }
                if (r != null
                        && s.round == r
                        && r.flight == null
                        && System.nanoTime() - r.lastActivity > 600_000_000_000L) fail(s, r);
                var q = BandwidthThrottler.INSTANCE.getQueue(s.id);
                if (q == null) continue;
                var packet = q.peekNext();
                if (packet == null) continue;
                if (r == null
                        || r.flight == null
                        || packet.batchId() != r.flight.id
                        || packet.streamEpoch() != s.epoch
                        || !packet.dimension().equals(p.level().dimension().identifier())) {
                    q.pollNext();
                    continue;
                }
                int limit =
                        Math.min(
                                16,
                                Math.min(
                                        config.sectionsPerPacket,
                                        s.maxSections <= 0
                                                ? config.maxSectionsPerTickPerPlayer
                                                : Math.min(
                                                        s.maxSections,
                                                        config.maxSectionsPerTickPerPlayer)));
                if (packet.payload().sectionCount() > limit) {
                    q.pollNext();
                    r.flight = null;
                    r.retryAt = 0;
                    wake.request();
                    continue;
                }
                if (!BandwidthThrottler.INSTANCE.canSend(s.id, packet.byteSize())) continue;
                q.pollNext();
                ServerPlayNetworking.send(p, packet.payload());
                r.flight.receipt.sent(tick);
                sentNodes += packet.payload().sectionCount();
                s.sent += packet.payload().sectionCount();
            }
        }
        if (config.debugTrackingEnabled && tick % config.debugTrackingInterval == 0) logSyncStats();
    }

    public void onDimensionChange(ServerPlayer p, ServerLevel destination) {
        var s = players.get(p.getUUID());
        if (s != null && s.protocolOk) session(p, s);
    }

    public void clearDimensionForReadyPlayers(ServerLevel level) {
        for (var s : players.values()) {
            var p = level.getServer().getPlayerList().getPlayer(s.id);
            if (p != null && p.level() == level && s.protocolOk) session(p, s);
        }
    }

    public void updateConfig(VoxyServerConfig cfg) {
        var previous = config;
        config = cfg;
        source.dirty.updateTiming(
                cfg.maintenanceQuietMillis * 1_000_000L,
                cfg.maintenanceCooldownMillis * 1_000_000L,
                cfg.maintenanceCooldownJitterMillis * 1_000_000L);
        BandwidthThrottler.INSTANCE.updateAllBandwidth(cfg.maxBandwidthBytesPerSec);
        if (server != null
                && (cfg.maxClientVoxyRadius != previous.maxClientVoxyRadius
                        || cfg.maxSectionsPerTickPerPlayer != previous.maxSectionsPerTickPerPlayer))
            for (var s : players.values()) {
                var p = server.getPlayerList().getPlayer(s.id);
                if (p != null && s.protocolOk) settings(p);
            }
    }

    public int pendingSourceSections() {
        return source.dirty.dirtyCells();
    }

    public long sourceUpdates() {
        return source.updates;
    }

    public long sentNodeCount() {
        return sentNodes;
    }

    public void markSourceColumn(ServerLevel l, net.minecraft.world.level.chunk.LevelChunk c) {
        if (!closed) {
            source.markColumn(l, c, config.sourceColumnRecheckSeconds);
            requestMaintenance();
        }
    }

    public void markSourceDirty(ServerLevel l, int x, int y, int z) {
        if (!closed) {
            source.mark(l, x, y, z);
            requestMaintenance();
        }
    }

    public void shutdown() {
        if (closed) return;
        closed = true;
        source.stopCaptures();
        if (server != null)
            for (var p : server.getPlayerList().getPlayers())
                try {
                    if (ServerPlayNetworking.canSend(p, LODProtocolPayload.TYPE))
                        ServerPlayNetworking.send(p, new LODProtocolPayload(0));
                } catch (RuntimeException ignored) {
                }
        worker.execute(source::checkpoint);
        worker.shutdown();
        boolean interrupted = false;
        while (!worker.isTerminated())
            try {
                worker.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                interrupted = true;
            }
        if (interrupted) Thread.currentThread().interrupt();
        for (var s : players.values()) {
            clear(s);
            BandwidthThrottler.INSTANCE.unregisterPlayer(s.id);
        }
        players.clear();
        source.release();
        server = null;
    }

    public record PlayerStat(String name, int sentCount, int chunkX, int chunkZ) {}

    public record StreamingStats(
            int players,
            int trackedSections,
            int pendingDirty,
            int queuedDirty,
            int loadedChunks,
            List<PlayerStat> perPlayer) {}

    public StreamingStats snapshotStats(MinecraftServer server) {
        var stats = new ArrayList<PlayerStat>();
        int pending = 0;
        for (var s : players.values()) {
            var p = server.getPlayerList().getPlayer(s.id);
            synchronized (s) {
                if (s.round != null) pending++;
                stats.add(
                        new PlayerStat(
                                p == null ? s.id.toString() : p.getName().getString(),
                                (int) Math.min(Integer.MAX_VALUE, s.sent),
                                p == null ? 0 : p.getBlockX() >> 4,
                                p == null ? 0 : p.getBlockZ() >> 4));
            }
        }
        int loaded = 0;
        for (var l : server.getAllLevels()) loaded += l.getChunkSource().getLoadedChunksCount();
        return new StreamingStats(
                players.size(),
                engine.versions().nodeCount(),
                source.dirty.dirtyCells(),
                pending,
                loaded,
                stats);
    }

    public void logSyncStats() {
        Voxyserver.LOGGER.info(
                "version sync: versionedNodes={} roots={} dirtyCells={} sourceUpdates={}"
                    + " sourceRetries={} versionIncrements={} sentLod0={} completed={} failed={}"
                    + " busyRejected={} ackTimeouts={} columnAudits={} skippedAudits={} cycles={}"
                    + " workerMs={} maxQuantumMs={} mainCaptureMs={}",
                engine.versions().nodeCount(),
                engine.versions().rootCount(),
                source.dirty.dirtyCells(),
                source.updates,
                source.retries,
                engine.versions().increments(),
                sentNodes,
                roundsCompleted,
                roundsFailed,
                busyRejected,
                ackTimeouts,
                source.columns.scheduled(),
                source.columns.skipped(),
                cycles,
                workerNanos / 1e6,
                maxQuantum / 1e6,
                source.captureNanos / 1e6);
        for (var s : players.values())
            synchronized (s) {
                var q = BandwidthThrottler.INSTANCE.getQueue(s.id);
                Voxyserver.LOGGER.info(
                        "version player {} ready={} protocolOk={} enabled={} requests={}"
                                + " sentLod0={} failedBatches={} retries={} scopeRejected={}"
                                + " activeRound={} queue={}",
                        s.id,
                        s.ready,
                        s.protocolOk,
                        s.enabled,
                        s.requests,
                        s.sent,
                        s.failedBatches,
                        s.retries,
                        s.scopeRejected,
                        s.round == null ? 0 : s.round.id,
                        q == null ? null : q.stats());
            }
    }
}
