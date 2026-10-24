package com.dripps.voxyserver.server;

import com.dripps.voxyserver.Voxyserver;
import com.dripps.voxyserver.config.VoxyServerConfig;
import com.dripps.voxyserver.network.*;
import com.dripps.voxyserver.server.throttle.*;
import com.dripps.voxyserver.sync.*;
import com.dripps.voxyserver.util.HashWorkBudget;

import me.cortex.voxy.commonImpl.WorldIdentifier;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.level.biome.Biome;

import java.util.*;
import java.util.concurrent.*;

/**
 * Client-driven native-LOD subscriptions. The server never enumerates a player's spatial grid or
 * the database.
 */
public final class LodStreamingService {
    private final ServerLodEngine engine;
    private volatile VoxyServerConfig config;
    private volatile MinecraftServer server;
    private volatile long tick;
    private final ConcurrentHashMap<UUID, State> players = new ConcurrentHashMap<>();
    private final DemandIndex index = new DemandIndex();
    private final SourceMaintenance source;
    private final ScheduledExecutorService worker =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        var t = new Thread(r, "VoxyServer Maintenance");
                        t.setDaemon(true);
                        t.setPriority(Thread.MIN_PRIORITY);
                        return t;
                    });
    private final HashWorkBudget heavyBudget = new HashWorkBudget(),
            hashBudget = new HashWorkBudget();
    private final FairWorkQueue<UUID, Request> requests = new FairWorkQueue<>(256, 4);
    private final DelayQueue<Send> ready = new DelayQueue<>();
    private final ConcurrentHashMap<GroupId, Set<UUID>> subscriptions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<GroupId, Boolean> changed = new ConcurrentHashMap<>();
    private long sentNodes, summaryMatches, detailRequests, requestsRejected;
    private boolean started, preferIndex;
    private long lastLeaseSweep, workTurn, lastCheckpoint;
    private volatile long workerCpuNanos, workerWallNanos, workerCycles, maxQuantumNanos;
    private final java.lang.management.ThreadMXBean cpu =
            java.lang.management.ManagementFactory.getThreadMXBean();

    private record GroupId(Identifier dimension, long key) {}

    private record Request(
            State state,
            LODDemandPayload payload,
            WorldIdentifier worldId,
            Registry<Biome> biomes) {}

    private record Send(State state, long group, long request, long epoch, long due)
            implements Delayed {
        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(due - System.nanoTime(), TimeUnit.NANOSECONDS);
        }

        @Override
        public int compareTo(Delayed other) {
            return Long.compare(due, ((Send) other).due);
        }
    }

    private GroupId notifyingGroup;
    private Iterator<UUID> notifying;

    private record Delivery(long group, long request, long slots) {}

    private static final class Gate extends DemandProofState {
        Gate(long request, long wanted) {
            super(request, wanted);
        }

        long expires, nextSend;
        int attempts;
        boolean queued, highPriority;
        WorldIdentifier worldId;
        Registry<Biome> biomes;
    }

    private static final class State {
        final UUID id;
        final PlayerLodTracker tracker = new PlayerLodTracker();
        final Map<Long, Gate> groups = new HashMap<>();
        final Map<Long, Delivery> deliveries = new HashMap<>();
        final com.dripps.voxyserver.util.BoundedLruCache<Long, Long> retired =
                new com.dripps.voxyserver.util.BoundedLruCache<>(4096);
        volatile Identifier dimension;
        long rateWindow;
        int rateUsed;

        State(UUID id) {
            this.id = id;
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
                        config.maintenanceCooldownMillis);
        engine.setDirtySectionListener(
                (dimension, key) -> {
                    index.invalidate(dimension, key);
                    GroupId group = new GroupId(dimension, DemandGroup.of(key));
                    if (subscriptions.containsKey(group)) changed.put(group, Boolean.TRUE);
                });
    }

    public void register() {
        ServerPlayConnectionEvents.JOIN.register(
                (handler, sender, server) -> {
                    var player = handler.getPlayer();
                    players.put(player.getUUID(), new State(player.getUUID()));
                    BandwidthThrottler.INSTANCE.registerPlayer(
                            player.getUUID(), config.maxBandwidthBytesPerSec);
                });
        ServerPlayConnectionEvents.DISCONNECT.register(
                (handler, server) -> {
                    var state = players.remove(handler.getPlayer().getUUID());
                    if (state != null) closeState(state);
                    BandwidthThrottler.INSTANCE.unregisterPlayer(handler.getPlayer().getUUID());
                });
        ServerPlayNetworking.registerGlobalReceiver(
                LODHandshakePayload.TYPE,
                (payload, ctx) -> {
                    var state = players.get(ctx.player().getUUID());
                    if (state == null) return;
                    state.tracker.setReady(true);
                    state.tracker.setProtocolOk(
                            payload.protocol() == VoxyServerNetworking.PROTOCOL_VERSION);
                    if (ServerPlayNetworking.canSend(ctx.player(), LODProtocolPayload.TYPE))
                        ServerPlayNetworking.send(
                                ctx.player(),
                                new LODProtocolPayload(VoxyServerNetworking.PROTOCOL_VERSION));
                    if (state.tracker.isProtocolOk()) {
                        sendSettings(ctx.player());
                        newSession(ctx.player(), state);
                    }
                });
        ServerPlayNetworking.registerGlobalReceiver(
                LODPreferencesPayload.TYPE,
                (p, ctx) -> {
                    var state = players.get(ctx.player().getUUID());
                    if (state == null) return;
                    state.tracker.setLodEnabled(p.enabled());
                    state.tracker.setPreferredRadius(p.lodStreamRadius());
                    state.tracker.setPreferredMaxSections(p.maxSectionsPerTick());
                    if (state.tracker.isReady() && state.tracker.isProtocolOk())
                        newSession(ctx.player(), state);
                });
        ServerPlayNetworking.registerGlobalReceiver(
                LODDemandPayload.TYPE, (p, ctx) -> receive(ctx.player(), p));
        ServerPlayNetworking.registerGlobalReceiver(
                LODBatchResultPayload.TYPE,
                (p, ctx) -> {
                    var state = players.get(ctx.player().getUUID());
                    if (state == null) return;
                    synchronized (state) {
                        if (!state.tracker.completeDemandBatch(
                                p.streamEpoch(),
                                p.batchId(),
                                p.status() == LODBatchResultPayload.SUCCESS,
                                tick)) return;
                        Delivery delivered = state.deliveries.remove(p.batchId());
                        if (delivered == null) return;
                        Gate gate = state.groups.get(delivered.group);
                        if (gate != null && gate.request == delivered.request) {
                            if (p.status() == LODBatchResultPayload.SUCCESS) {
                                gate.confirm(delivered.request, delivered.slots, true);
                                gate.attempts = 0;
                            } else {
                                gate.nextSend = System.nanoTime() + 5_000_000_000L;
                                gate.attempts++;
                            }
                            schedule(state, delivered.group, gate);
                        }
                    }
                });
        ServerTickEvents.END_SERVER_TICK.register(this::onTick);
        worker.scheduleWithFixedDelay(this::cycle, 50, 50, TimeUnit.MILLISECONDS);
    }

    private void receive(ServerPlayer player, LODDemandPayload p) {
        State state = players.get(player.getUUID());
        if (state == null
                || !state.tracker.isReady()
                || !state.tracker.isProtocolOk()
                || !state.tracker.isLodEnabled()
                || p.epoch() != state.tracker.streamEpoch()
                || !p.dimension().equals(player.level().dimension().identifier())) return;
        synchronized (state) {
            long window = tick / 20;
            if (state.rateWindow != window) {
                state.rateWindow = window;
                state.rateUsed = 0;
            }
            if (state.rateUsed >= config.maxDemandRequestsPerSecond) {
                requestsRejected++;
                return;
            }
            state.rateUsed++;
        }
        int radius = state.tracker.getEffectiveRadius(config.maxClientVoxyRadius);
        if (p.phase() != LODDemandPayload.CANCEL)
            for (int i = 0; i < 64; i++)
                if ((p.wanted() & (1L << i)) != 0
                        && !DemandGroup.allowed(
                                DemandGroup.node(p.group(), i),
                                player.getBlockX(),
                                player.getBlockZ(),
                                radius,
                                player.level().getMinY(),
                                player.level().getMaxY())) {
                    requestsRejected++;
                    reply(state, p, LODDemandReplyPayload.REJECTED, 0, 0, 0);
                    return;
                }
        WorldIdentifier world = WorldIdentifier.of(player.level());
        if (world == null) return;
        if (!requests.offer(
                state.id,
                new Request(
                        state,
                        p,
                        world,
                        player.level().registryAccess().lookupOrThrow(Registries.BIOME)))) {
            requestsRejected++;
            reply(state, p, LODDemandReplyPayload.PENDING, 0, 0, 0);
        }
    }

    private void process(Request request) {
        var state = request.state;
        var p = request.payload;
        if (players.get(state.id) != state || p.epoch() != state.tracker.streamEpoch()) return;
        synchronized (state) {
            if (players.get(state.id) != state || p.epoch() != state.tracker.streamEpoch()) return;
            Long retired = state.retired.get(p.group());
            if (retired != null && p.requestId() <= retired) return;
            Gate previous = state.groups.get(p.group());
            if (previous != null && p.requestId() < previous.request) return;
            if (p.phase() == LODDemandPayload.CANCEL) {
                if (previous != null && p.requestId() >= previous.request) {
                    supersede(state, p.group(), previous.wanted);
                    state.groups.remove(p.group());
                    unsubscribe(state, p.group());
                }
                state.retired.put(p.group(), p.requestId());
                return;
            }
            if (previous == null || p.requestId() > previous.request) {
                if (p.phase() != LODDemandPayload.SUMMARY
                        || (previous == null && state.groups.size() >= 1024)) {
                    reply(state, p, LODDemandReplyPayload.REJECTED, 0, 0, 0);
                    return;
                }
                if (previous != null) supersede(state, p.group(), previous.wanted);
                var gate = new Gate(p.requestId(), p.wanted());
                gate.highPriority = previous != null && previous.invalidated;
                gate.worldId = request.worldId;
                gate.biomes = request.biomes;
                gate.expires = System.nanoTime() + 600_000_000_000L;
                state.groups.put(p.group(), gate);
                subscriptions
                        .computeIfAbsent(
                                new GroupId(p.dimension(), p.group()),
                                k -> ConcurrentHashMap.newKeySet())
                        .add(state.id);
                previous = gate;
            }
            if (previous.wanted != p.wanted()) {
                reply(state, p, LODDemandReplyPayload.REJECTED, 0, 0, 0);
                return;
            }
        }
        DemandIndex.Proof proof = index.proof(p.dimension(), p.group(), p.wanted());
        if (proof == null) {
            for (int i = 0; i < 64; i++)
                if ((p.wanted() & (1L << i)) != 0) {
                    long key = DemandGroup.node(p.group(), i);
                    if (index.get(p.dimension(), key) == null)
                        index.request(p.dimension(), key, request.worldId, request.biomes);
                }
            reply(state, p, LODDemandReplyPayload.PENDING, 0, 0, 0);
            return;
        }
        synchronized (index) {
            synchronized (state) {
                if (p.epoch() != state.tracker.streamEpoch()
                        || !index.current(p.dimension(), p.group(), p.wanted(), proof.revision()))
                    return;
                Gate gate = state.groups.get(p.group());
                if (gate == null || gate.request != p.requestId()) return;
                gate.expires = System.nanoTime() + 600_000_000_000L;
                if (gate.invalidated) {
                    reply(
                            state,
                            p,
                            LODDemandReplyPayload.INVALIDATED,
                            gate.coverage,
                            gate.revision,
                            0);
                    return;
                }
                long present = proof.coverage(), revision = proof.revision();
                long[] hashes = proof.hashes();
                if (p.phase() == LODDemandPayload.SUMMARY) {
                    int status =
                            gate.summary(
                                    present,
                                    revision,
                                    hashes,
                                    p.coverage(),
                                    p.root(),
                                    config.hashSyncEnabled);
                    if (status == LODDemandReplyPayload.MATCH) summaryMatches++;
                    reply(state, p, status, gate.coverage, gate.revision, gate.needed);
                } else {
                    boolean alreadyDetailed = gate.detailed;
                    int status =
                            gate.detail(
                                    present,
                                    revision,
                                    p.revision(),
                                    p.coverage(),
                                    p.hashes(),
                                    config.hashSyncEnabled);
                    if (status == LODDemandReplyPayload.INVALIDATED) {
                        invalidate(state, p.group(), gate);
                        return;
                    }
                    if (!alreadyDetailed) detailRequests++;
                    reply(state, p, status, gate.coverage, gate.revision, gate.needed);
                    schedule(state, p.group(), gate);
                }
            }
        }
    }

    private void schedule(State state, long group, Gate gate) {
        if (gate.needed != 0 && !gate.invalidated && !gate.queued) {
            gate.queued = true;
            ready.add(
                    new Send(
                            state,
                            group,
                            gate.request,
                            state.tracker.streamEpoch(),
                            gate.nextSend));
        }
    }

    private void sendNodes(Send work) {
        State state = work.state;
        Gate gate;
        long wanted;
        Identifier dimension;
        synchronized (state) {
            gate = state.groups.get(work.group);
            if (gate == null
                    || gate.request != work.request
                    || work.epoch != state.tracker.streamEpoch()) return;
            gate.queued = false;
            if (gate.needed == 0) return;
            if (gate.nextSend > System.nanoTime()) {
                schedule(state, work.group, gate);
                return;
            }
            if (gate.attempts >= 8) {
                invalidate(state, work.group, gate);
                return;
            }
            wanted = gate.needed;
            dimension = state.dimension;
        }
        var batch = new ArrayList<LODSectionPayload>();
        long slots = 0;
        for (int i = 0;
                i < 64
                        && batch.size()
                                < Math.min(
                                        Math.min(16, config.sectionsPerPacket),
                                        state.tracker.getEffectiveMaxSections(
                                                config.maxSectionsPerTickPerPlayer));
                i++)
            if ((wanted & (1L << i)) != 0) {
                long key = DemandGroup.node(work.group, i);
                if (state.tracker.hasOutstanding(key)) continue;
                var node = index.get(dimension, key);
                if (node == null || !node.present()) {
                    synchronized (state) {
                        invalidate(state, work.group, gate);
                    }
                    return;
                }
                var payload = index.payload(new DemandIndex.Key(dimension, key));
                if (payload == null) {
                    index.request(dimension, key, gate.worldId, gate.biomes);
                    continue;
                }
                if (payload.contentHash() != gate.hashes[i]) {
                    synchronized (state) {
                        invalidate(state, work.group, gate);
                    }
                    return;
                }
                batch.add(payload);
                slots |= 1L << i;
            }
        if (batch.isEmpty()) {
            synchronized (state) {
                gate.nextSend = System.nanoTime() + 500_000_000L;
                schedule(state, work.group, gate);
            }
            return;
        }
        // Encoding, registry resolution and storage are exclusively on this worker, outside
        // player-state monitors.
        var packet =
                PreSerializedLodPayload.fromBulk(
                                new LODBulkPayload(dimension, List.copyOf(batch)),
                                server.registryAccess())
                        .withDemand(work.group, work.request);
        long[] keys = batch.stream().mapToLong(LODSectionPayload::sectionKey).toArray();
        long[] hashes = batch.stream().mapToLong(LODSectionPayload::contentHash).toArray();
        synchronized (index) {
            synchronized (state) {
                if (state.groups.get(work.group) != gate
                        || gate.request != work.request
                        || work.epoch != state.tracker.streamEpoch()) return;
                if (!index.current(dimension, work.group, gate.wanted, gate.revision)) {
                    invalidate(state, work.group, gate);
                    return;
                }
                var delivery =
                        state.tracker.reserveBatch(
                                dimension,
                                state.tracker.streamEpoch(),
                                keys,
                                hashes,
                                packet.data().length,
                                tick);
                if (delivery == null) {
                    gate.nextSend = System.nanoTime() + 1_000_000_000L;
                    schedule(state, work.group, gate);
                    return;
                }
                state.deliveries.put(
                        delivery.batchId(), new Delivery(work.group, work.request, slots));
                packet = packet.withDelivery(delivery.streamEpoch(), delivery.batchId());
                var evicted = new ArrayList<PlayerOutboundQueue.QueuedPacket>();
                boolean accepted =
                        BandwidthThrottler.INSTANCE.enqueuePacket(
                                state.id,
                                new PlayerOutboundQueue.QueuedPacket(
                                        packet,
                                        dimension,
                                        delivery.streamEpoch(),
                                        delivery.batchId(),
                                        gate.highPriority,
                                        System.nanoTime()),
                                evicted);
                for (var old : evicted) failQueued(state, old);
                if (!accepted)
                    failQueued(
                            state,
                            new PlayerOutboundQueue.QueuedPacket(
                                    packet,
                                    dimension,
                                    delivery.streamEpoch(),
                                    delivery.batchId(),
                                    false,
                                    0));
            }
        }
    }

    private void failQueued(State state, PlayerOutboundQueue.QueuedPacket packet) {
        state.tracker.completeDemandBatch(packet.streamEpoch(), packet.batchId(), false, tick);
        Delivery old = state.deliveries.remove(packet.batchId());
        if (old == null) return;
        Gate gate = state.groups.get(old.group);
        if (gate != null && gate.request == old.request) {
            gate.attempts++;
            gate.nextSend = System.nanoTime() + 5_000_000_000L;
            schedule(state, old.group, gate);
        }
    }

    private void supersede(State state, long group, long mask) {
        var keys = new long[Long.bitCount(mask)];
        int n = 0;
        for (int i = 0; i < 64; i++)
            if ((mask & (1L << i)) != 0) keys[n++] = DemandGroup.node(group, i);
        state.tracker.supersedeSections(keys);
        var deliveries = state.deliveries.entrySet().iterator();
        while (deliveries.hasNext()) {
            var old = deliveries.next();
            if (old.getValue().group != group) continue;
            state.tracker.completeDemandBatch(
                    state.tracker.streamEpoch(), old.getKey(), false, tick);
            deliveries.remove();
        }
    }

    private void invalidate(State state, long group, Gate gate) {
        if (state.groups.get(group) != gate || gate.invalidated) return;
        gate.invalidated = true;
        supersede(state, group, gate.wanted);
        gate.needed = 0;
        send(
                state,
                new LODDemandReplyPayload(
                        state.dimension,
                        state.tracker.streamEpoch(),
                        gate.request,
                        group,
                        LODDemandReplyPayload.INVALIDATED,
                        gate.wanted,
                        gate.coverage,
                        gate.revision,
                        0));
    }

    private void unsubscribe(State state, long group) {
        var id = new GroupId(state.dimension, group);
        subscriptions.computeIfPresent(
                id,
                (key, set) -> {
                    set.remove(state.id);
                    return set.isEmpty() ? null : set;
                });
    }

    private void reply(
            State state,
            LODDemandPayload p,
            int status,
            long coverage,
            long revision,
            long needed) {
        send(
                state,
                new LODDemandReplyPayload(
                        p.dimension(),
                        p.epoch(),
                        p.requestId(),
                        p.group(),
                        status,
                        p.wanted(),
                        coverage,
                        revision,
                        needed));
    }

    private void send(State state, LODDemandReplyPayload reply) {
        var current = server;
        if (current == null) return;
        current.execute(
                () -> {
                    ServerPlayer p = current.getPlayerList().getPlayer(state.id);
                    if (p != null
                            && players.get(state.id) == state
                            && reply.epoch() == state.tracker.streamEpoch()
                            && p.level().dimension().identifier().equals(reply.dimension()))
                        ServerPlayNetworking.send(p, reply);
                });
    }

    private void cycle() {
        if (server == null) return;
        long cycleStarted = System.nanoTime(),
                cpuStarted =
                        cpu.isCurrentThreadCpuTimeSupported() ? cpu.getCurrentThreadCpuTime() : 0;
        try {
            long now = System.nanoTime();
            int idleTurns = 0;
            // Yield between maintenance turns even when the administrator grants >=1 s/s.
            while (now-cycleStarted<50_000_000L && heavyBudget.tryAcquire(
                    now / 50_000_000L,
                    20,
                    Integer.MAX_VALUE,
                    TimeUnit.MILLISECONDS.toNanos(config.maintenanceMillisPerSecond))) {
                long began = System.nanoTime();
                boolean did = false;
                switch ((int) (workTurn++ % 5)) {
                    case 0 -> {
                        Request request = requests.poll();
                        if (request != null) {
                            process(request);
                            did = true;
                        }
                    }
                    case 1 -> {
                        preferIndex = !preferIndex;
                        did = preferIndex ? hashOne(now) : source.step(server);
                    }
                    case 2 -> {
                        Send send = ready.poll();
                        if (send != null) {
                            if (players.get(send.state.id) == send.state) sendNodes(send);
                            did = true;
                        }
                    }
                    case 3 -> did = preferIndex ? source.step(server) : hashOne(now);
                    default -> did = notifyOne();
                }
                long elapsed = System.nanoTime() - began;
                maxQuantumNanos = Math.max(maxQuantumNanos, elapsed);
                heavyBudget.recordNanos(elapsed);
                now = System.nanoTime();
                idleTurns = did ? 0 : idleTurns + 1;
                if (idleTurns >= 5) break;
            }
            if (now - lastLeaseSweep >= 5_000_000_000L) {
                lastLeaseSweep = now;
                expireLeases(now);
            }
            if (now - lastCheckpoint >= 30_000_000_000L) {
                lastCheckpoint = now;
                source.checkpoint();
            }
        } catch (Throwable error) {
            Voxyserver.LOGGER.error("LOD maintenance cycle failed", error);
        } finally {
            workerCycles++;
            workerWallNanos += System.nanoTime() - cycleStarted;
            if (cpuStarted > 0)
                workerCpuNanos += Math.max(0, cpu.getCurrentThreadCpuTime() - cpuStarted);
        }
    }

    private boolean hashOne(long now) {
        if (index.pending() == 0) return false;
        return index.computeOne(
                engine,
                () ->
                        hashBudget.tryAcquire(
                                System.nanoTime() / 50_000_000L,
                                config.hashWorkIntervalTicks,
                                config.maxHashComputationsPerInterval,
                                TimeUnit.MILLISECONDS.toNanos(config.maxHashMillisPerInterval)),
                hashBudget::recordNanos);
    }

    private boolean notifyOne() {
        if (notifying == null || !notifying.hasNext()) {
            notifying = null;
            var it = changed.keySet().iterator();
            if (!it.hasNext()) return false;
            notifyingGroup = it.next();
            if (changed.remove(notifyingGroup) == null) return false;
            var subscribers = subscriptions.get(notifyingGroup);
            if (subscribers == null) return true;
            notifying = subscribers.iterator();
            if (!notifying.hasNext()) return true;
        }
        State state = players.get(notifying.next());
        if (state != null)
            synchronized (state) {
                Gate gate = state.groups.get(notifyingGroup.key);
                if (gate != null) invalidate(state, notifyingGroup.key, gate);
            }
        return true;
    }

    private void expireLeases(long now) {
        for (State state : players.values())
            synchronized (state) {
                var it = state.groups.entrySet().iterator();
                while (it.hasNext()) {
                    var e = it.next();
                    Gate gate = e.getValue();
                    if (now < gate.expires) continue;
                    supersede(state, e.getKey(), gate.wanted);
                    unsubscribe(state, e.getKey());
                    state.retired.put(e.getKey(), gate.request);
                    it.remove();
                }
            }
    }

    private void onTick(MinecraftServer server) {
        this.server = server;
        tick++;
        if (!started) {
            started = true;
            Map<String, ServerLevel> levels = new HashMap<>();
            for (var level : server.getAllLevels())
                levels.put(level.dimension().identifier().toString(), level);
            worker.execute(() -> source.resume(levels));
        }
        for (var entry : players.entrySet()) {
            var state = entry.getValue();
            var player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) continue;
            BandwidthThrottler.INSTANCE.tick(state.id, tick);
            synchronized (state) {
                for (long batch : state.tracker.expiredDemandBatches(tick, 600)) {
                    state.tracker.completeDemandBatch(
                            state.tracker.streamEpoch(), batch, false, tick);
                    Delivery old = state.deliveries.remove(batch);
                    if (old == null) continue;
                    Gate gate = state.groups.get(old.group);
                    if (gate != null && gate.request == old.request) {
                        gate.attempts++;
                        gate.nextSend = System.nanoTime() + 5_000_000_000L;
                        schedule(state, old.group, gate);
                    }
                }
            }
            var queue = BandwidthThrottler.INSTANCE.getQueue(state.id);
            if (queue == null) continue;
            synchronized (state) {
                synchronized (queue) {
                    synchronized (state.tracker) {
                        int sent = 0,
                                limit =
                                        state.tracker.getEffectiveMaxSections(
                                                config.maxSectionsPerTickPerPlayer);
                        while (true) {
                            var packet = queue.peekNext();
                            if (packet == null) break;
                            if (!packet.dimension().equals(player.level().dimension().identifier())
                                    || !state.tracker.isBatchSendable(
                                            packet.streamEpoch(), packet.batchId())) {
                                queue.pollNext();
                                failQueued(state, packet);
                                continue;
                            }
                            if (sent + packet.payload().sectionCount() > limit) break;
                            if (!BandwidthThrottler.INSTANCE.canSend(state.id, packet.byteSize()))
                                break;
                            queue.pollNext();
                            ServerPlayNetworking.send(player, packet.payload());
                            sentNodes += packet.payload().sectionCount();
                            sent += packet.payload().sectionCount();
                        }
                    }
                }
            }
        }
        if (config.debugTrackingEnabled && tick % config.debugTrackingInterval == 0) logSyncStats();
    }

    private void closeState(State state) {
        requests.remove(state.id);
        synchronized (state) {
            for (long group : new ArrayList<>(state.groups.keySet())) unsubscribe(state, group);
            state.groups.clear();
            state.deliveries.clear();
            state.tracker.reset();
        }
        BandwidthThrottler.INSTANCE.clearPlayerQueue(state.id);
    }

    private void newSession(ServerPlayer player, State state) {
        synchronized (state) {
            closeState(state);
            state.retired.clear();
            state.dimension = player.level().dimension().identifier();
            var session = state.tracker.beginSession(state.dimension, false, tick + 60);
            ServerPlayNetworking.send(
                    player,
                    new LODStreamSessionPayload(
                            state.dimension,
                            session.streamEpoch(),
                            session.manifestRequestId(),
                            false,
                            engine.getWorldIdentity(),
                            com.dripps.voxyserver.util.SemanticSectionHash.VERSION,
                            new ManifestScope(
                                    player.getBlockX() >> 5,
                                    player.getBlockZ() >> 5,
                                    state.tracker.getEffectiveRadius(config.maxClientVoxyRadius)
                                            >> 1)));
        }
    }

    private void sendSettings(ServerPlayer player) {
        ServerPlayNetworking.send(player, new LODHashSyncSettingsPayload(config.hashSyncEnabled));
        ServerPlayNetworking.send(
                player,
                new LODServerSettingsPayload(
                        config.maxClientVoxyRadius, config.maxSectionsPerTickPerPlayer));
    }

    public int pendingSourceSections() {return source.dirty.dirtyCells();}

    public void markSourceDirty(ServerLevel level, int blockX, int blockY, int blockZ) {
        source.mark(level, blockX, blockY, blockZ);
    }

    public void onDimensionChange(ServerPlayer player, ServerLevel destination) {
        State state = players.get(player.getUUID());
        if (state != null && state.tracker.isProtocolOk()) newSession(player, state);
    }

    public void clearDimensionForReadyPlayers(ServerLevel level) {
        index.clear();
        for (var state : players.values()) {
            var player = level.getServer().getPlayerList().getPlayer(state.id);
            if (player != null && player.level() == level && state.tracker.isProtocolOk())
                newSession(player, state);
        }
    }

    public void updateConfig(VoxyServerConfig cfg) {
        config = cfg;
        source.dirty.updateTiming(
                cfg.maintenanceQuietMillis * 1_000_000L,
                cfg.maintenanceCooldownMillis * 1_000_000L);
        BandwidthThrottler.INSTANCE.updateAllBandwidth(cfg.maxBandwidthBytesPerSec);
        if (server != null)
            for (var state : players.values()) {
                var p = server.getPlayerList().getPlayer(state.id);
                if (p != null && state.tracker.isProtocolOk()) {
                    sendSettings(p);
                    newSession(p, state);
                }
            }
    }

    public void shutdown() {
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
        for (var state : players.values()) closeState(state);
        index.clear();
    }

    public record PlayerStat(String name, int sentCount, int chunkX, int chunkZ) {}

    public record StreamingStats(
            int players,
            int trackedSections,
            int hashCacheSize,
            int pendingDirty,
            int queuedDirty,
            int loadedChunks,
            List<PlayerStat> perPlayer) {}

    public StreamingStats snapshotStats(MinecraftServer s) {
        var stats = new ArrayList<PlayerStat>();
        players.forEach(
                (id, state) -> {
                    var p = s.getPlayerList().getPlayer(id);
                    stats.add(
                            new PlayerStat(
                                    p == null ? id.toString() : p.getName().getString(),
                                    state.tracker.sentCount(),
                                    p == null ? 0 : p.getBlockX() >> 4,
                                    p == null ? 0 : p.getBlockZ() >> 4));
                });
        return new StreamingStats(
                players.size(),
                index.size(),
                index.size(),
                source.dirty.dirtyCells(),
                index.pending(),
                0,
                stats);
    }

    public void logSyncStats() {
        Voxyserver.LOGGER.info(
                "demand sync: roots={} dirtyCells={} sourceUpdates={} sourceRetries={} indexed={}"
                    + " hashWork={} cacheHits={} absent={} matchedGroups={} details={} sentNodes={}"
                    + " rejected={}",
                source.dirty.roots(),
                source.dirty.dirtyCells(),
                source.updates,
                source.retries,
                index.size(),
                index.hashesComputed,
                index.cacheHits,
                index.absent,
                summaryMatches,
                detailRequests,
                sentNodes,
                requestsRejected);
        Voxyserver.LOGGER.info(
                "maintenance: cycles={} cpuMs={} wallMs={} maxQuantumMs={} captureCount={}"
                        + " mainCaptureMs={} hashMs={}",
                workerCycles,
                workerCpuNanos / 1e6,
                workerWallNanos / 1e6,
                maxQuantumNanos / 1e6,
                source.captureCount,
                source.captureNanos / 1e6,
                index.hashNanos / 1e6);
        players.forEach(
                (id, state) -> {
                    var q = BandwidthThrottler.INSTANCE.getQueue(id);
                    Voxyserver.LOGGER.info(
                            "demand player {} {} queue={}",
                            id,
                            state.tracker.syncStats(),
                            q == null ? null : q.stats());
                });
    }

    // Only the retained v4 tracker regression fixtures use this encoding. Protocol 5 uses native
    // keys per dimension.
    static long composeSectionKey(int dimension, long key) {
        return key | ((long) (dimension & 15) << 60);
    }

    static long extractSectionKey(long key) {
        return key & 0x0fffffffffffffffL;
    }

    static int extractSectionDimOrdinal(long key) {
        return (int) (key >>> 60) & 15;
    }
}
