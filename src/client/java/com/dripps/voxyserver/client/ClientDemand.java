package com.dripps.voxyserver.client;

import com.dripps.voxyserver.network.*;
import com.dripps.voxyserver.sync.*;
import com.dripps.voxyserver.util.SemanticPalette;

import me.cortex.voxy.common.world.*;
import me.cortex.voxy.commonImpl.WorldIdentifier;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.biome.Biome;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Enumerates active renderer subscriptions, never the database. One worker plus the remote ingest
 * worker.
 */
public final class ClientDemand {
    private static final int WAIT_DATA = ClientDemandState.WAIT_DATA;
    private static final ScheduledExecutorService WORKER =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        var t = new Thread(r, "VoxyServer Client Demand");
                        t.setDaemon(true);
                        return t;
                    });

    private record Context(
            long generation,
            Identifier dimension,
            long epoch,
            WorldIdentifier identifier,
            Registry<Biome> biomes,
            int x,
            int z,
            int radius,
            int minY,
            int maxY) {}

    private record Identity(WorldEngine world, long node) {}

    private record Received(long epoch, long request, byte children, long version) {}

    private static final class Watch {
        final WorldEngine world;
        final long node;
        final AtomicLong version = new AtomicLong();
        final Set<Object> owners = Collections.newSetFromMap(new WeakHashMap<>());
        Runnable pending;
        volatile boolean localPresent;
        volatile byte serverChildren = (byte) 255;
        volatile long remoteVersion = -1;
        long hash, hashedVersion = -1, audit;
        volatile long absentEpoch;

        Watch(WorldEngine world, long node) {
            this.world = world;
            this.node = node;
        }

        synchronized boolean defer(Runnable callback, long epoch) {
            if (localPresent || absentEpoch == epoch) return false;
            pending = callback;
            return true;
        }

        synchronized Runnable takeCallback() {
            var cb = pending;
            pending = null;
            return cb;
        }

        synchronized void own(Object owner) {
            owners.add(owner);
        }

        synchronized boolean disown(Object owner) {
            owners.remove(owner);
            return owners.isEmpty();
        }
    }

    private static final class Group extends ClientDemandState {
        final long[] hashes = new long[64];
        long audit;
        boolean building;
    }

    // Interests survive an epoch change: already rendered geometry does not issue another watch()
    // then.
    private static final ConcurrentHashMap<Identity, Watch> WATCHES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Identity, Received> RECEIVED = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, Group> GROUPS = new ConcurrentHashMap<>();
    private static final ArrayDeque<Long> ORDER = new ArrayDeque<>(); // worker only
    private static final ArrayBlockingQueue<LODDemandReplyPayload> REPLIES =
            new ArrayBlockingQueue<>(256);
    private static final Map<Object, WorldEngine> ROUTERS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final AtomicLong GENERATION = new AtomicLong(),
            IDS = new AtomicLong(),
            INTERESTS = new AtomicLong();
    private static final ThreadLocal<Boolean> RESUMING = ThreadLocal.withInitial(() -> false);
    private static volatile Context context;
    private static LODStreamSessionPayload pendingSession;
    private static long workerGeneration = -1, interestVersion = -1, audits;
    private static int centerX, centerZ, radius;

    static {
        SectionDataAccess.observer = ClientDemand::localChanged;
        WORKER.scheduleWithFixedDelay(ClientDemand::cycle, 25, 50, TimeUnit.MILLISECONDS);
    }

    private ClientDemand() {}

    public static void start(LODStreamSessionPayload p) {
        reset();
        pendingSession = p;
        tryActivateSession();
    }

    private static void tryActivateSession() {
        var p = pendingSession;
        if (p == null || !ClientLodSettings.isProtocolOk() || !ClientLodSettings.isEnabled())
            return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null
                || mc.level == null
                || mc.player == null
                || !p.dimension().equals(mc.level.dimension().identifier())) return;
        var level = mc.level;
        var player = mc.player;
        WorldIdentifier id = WorldIdentifier.of(level);
        if (id == null) return;
        pendingSession = null;
        com.dripps.voxyserver.Voxyserver.LOGGER.info(
                "remote LOD session active: dimension={} epoch={} radiusChunks={}",
                p.dimension(),
                p.streamEpoch(),
                effectiveRadius());
        context =
                new Context(
                        GENERATION.incrementAndGet(),
                        p.dimension(),
                        p.streamEpoch(),
                        id,
                        level.registryAccess().lookupOrThrow(Registries.BIOME),
                        player.getBlockX(),
                        player.getBlockZ(),
                        effectiveRadius(),
                        level.getMinY(),
                        level.getMaxY());
    }

    public static void tick() {
        if (pendingSession != null
                && ClientLodSettings.isProtocolOk()
                && ClientLodSettings.isEnabled()) tryActivateSession();
        Context c = context;
        if (c == null) return;
        Minecraft mc = Minecraft.getInstance();
        var player = mc.player;
        if (player == null
                || mc.level == null
                || !ClientLodSettings.isProtocolOk()
                || !ClientLodSettings.isEnabled()) {
            reset();
            return;
        }
        context =
                new Context(
                        c.generation,
                        c.dimension,
                        c.epoch,
                        c.identifier,
                        c.biomes,
                        player.getBlockX(),
                        player.getBlockZ(),
                        effectiveRadius(),
                        c.minY,
                        c.maxY);
    }

    private static int effectiveRadius() {
        int preferred = ClientLodSettings.getPreferredRadius(),
                max = ClientLodSettings.getServerMaxRadius();
        return Math.max(0, preferred <= 0 ? max : Math.min(preferred, max));
    }

    public static void reset() {
        pendingSession = null;
        context = null;
        GENERATION.incrementAndGet();
    }

    public static void receive(LODDemandReplyPayload p) {
        REPLIES.offer(p);
    }

    public static void bindRouter(Object router, WorldEngine world) {
        ROUTERS.put(router, world);
    }

    public static void watch(Object router, long key) {
        WorldEngine world = ROUTERS.get(router);
        if (world == null) return;
        Watch w = observe(world, key);
        if (w != null) {
            w.own(router);
            INTERESTS.incrementAndGet();
        }
    }

    public static void unwatch(Object router, long key) {
        WorldEngine world = ROUTERS.get(router);
        if (world == null) return;
        Identity id = new Identity(world, key);
        Watch w = WATCHES.get(id);
        if (w != null && w.disown(router)) {
            WATCHES.remove(id, w);
            w.takeCallback();
            INTERESTS.incrementAndGet();
        }
    }

    public static boolean defer(WorldEngine world, long key, Runnable callback) {
        Context c = context;
        if (RESUMING.get()
                || c == null
                || !ClientLodSettings.isEnabled()
                || !ClientLodSettings.isProtocolOk()
                || !DemandGroup.allowed(key, c.x, c.z, c.radius, c.minY, c.maxY)) return false;
        Watch w = observe(world, key);
        return w != null && w.defer(callback, c.epoch);
    }

    private static Watch observe(WorldEngine world, long key) {
        if (!DemandGroup.valid(key)) return null;
        Identity id = new Identity(world, key);
        Watch w = WATCHES.get(id);
        if (w != null) return w;
        if (WATCHES.size() >= 65_536)
            return null; // Ordinary Voxy rendering remains available on overflow.
        var fresh = new Watch(world, key);
        w = WATCHES.putIfAbsent(id, fresh);
        if (w == null) {
            INTERESTS.incrementAndGet();
            return fresh;
        }
        return w;
    }

    private static void localChanged(WorldEngine world, long key) {
        Watch w = WATCHES.get(new Identity(world, key));
        if (w != null) w.version.incrementAndGet();
    }

    /**
     * Called under the native world monitor, after the write, before another native writer can run.
     */
    public static void nodeReceived(
            WorldEngine world, long key, byte children, long epoch, long request) {
        Identity id = new Identity(world, key);
        Watch w = WATCHES.get(id);
        if (w != null) RECEIVED.put(id, new Received(epoch, request, children, w.version.get()));
    }

    public static boolean install(PreSerializedLodPayload p, long key, Runnable write) {
        Context c = context;
        if (c == null || c.epoch != p.streamEpoch() || DemandGroup.of(key) != p.demandGroup())
            return false;
        Group g = GROUPS.get(p.demandGroup());
        return g != null
                && g.lease.apply(
                        p.streamEpoch(),
                        p.demandRequestId(),
                        DemandGroup.slot(key),
                        () -> {
                            Context current = context;
                            if (current == null || current.generation != c.generation)
                                throw new CancellationException("replaced session");
                            write.run();
                        });
    }

    private static void resume(Watch w) {
        Runnable callback = w.takeCallback();
        if (callback == null) return;
        RESUMING.set(true);
        try {
            callback.run();
        } finally {
            RESUMING.remove();
        }
    }

    private static void send(Context c, LODDemandPayload p) {
        Minecraft.getInstance()
                .execute(
                        () -> {
                            Context current = context;
                            if (current != null
                                    && current.generation == c.generation
                                    && Minecraft.getInstance().getConnection() != null)
                                ClientPlayNetworking.send(p);
                        });
    }

    private static void cancel(Context c, long key, Group g) {
        g.lease.invalidate();
        if (g.wanted != 0)
            send(
                    c,
                    new LODDemandPayload(
                            c.dimension,
                            c.epoch,
                            IDS.incrementAndGet(),
                            LODDemandPayload.CANCEL,
                            key,
                            g.wanted,
                            0,
                            0,
                            0,
                            new long[0]));
    }

    private static void reconcile(Context c, WorldEngine world) {
        Map<Long, Long> masks = new HashMap<>();
        WATCHES.forEach(
                (id, w) -> {
                    if (!id.world.isLive() || id.world != world) {
                        if (WATCHES.remove(id, w)) resume(w);
                        return;
                    }
                    if (DemandGroup.allowed(id.node, c.x, c.z, c.radius, c.minY, c.maxY))
                        masks.merge(
                                DemandGroup.of(id.node),
                                1L << DemandGroup.slot(id.node),
                                (a, b) -> a | b);
                    else resume(w);
                });
        for (var e : GROUPS.entrySet()) {
            long mask = masks.getOrDefault(e.getKey(), 0L);
            Group g = e.getValue();
            if (mask == g.wanted) continue;
            cancel(c, e.getKey(), g);
            if (mask == 0) GROUPS.remove(e.getKey(), g);
            else {
                g.wanted = mask;
                g.request = 0;
                g.verifiedRequest = 0;
                g.building = false;
                g.due = 0;
            }
        }
        var added =
                new ArrayList<>(
                        masks.entrySet().stream()
                                .filter(e -> !GROUPS.containsKey(e.getKey()))
                                .toList());
        added.sort(
                Comparator.<Map.Entry<Long, Long>>comparingInt(
                                e -> -WorldEngine.getLevel(e.getKey()))
                        .thenComparingDouble(e -> distanceSquared(e.getKey(), c)));
        // New parents are needed to unblock native refinement. Existing proofs still rotate fairly
        // afterwards.
        for (int i = added.size() - 1; i >= 0; i--) {
            var e = added.get(i);
            var g = new Group();
            g.wanted = e.getValue();
            GROUPS.put(e.getKey(), g);
            ORDER.addFirst(e.getKey());
        }
    }

    private static double distanceSquared(long group, Context c) {
        long edge = 128L << WorldEngine.getLevel(group);
        double x = WorldEngine.getX(group) * edge + edge / 2.0 - c.x,
                z = WorldEngine.getZ(group) * edge + edge / 2.0 - c.z;
        return x * x + z * z;
    }

    private static void replies(Context c, WorldEngine world) {
        LODDemandReplyPayload p;
        while ((p = REPLIES.poll()) != null) {
            if (p.epoch() != c.epoch || !p.dimension().equals(c.dimension)) continue;
            Group g = GROUPS.get(p.group());
            if (g == null || !g.onReply(p, System.nanoTime())) continue;
            for (int i = 0; i < 64; i++)
                if ((g.wanted & ~g.coverage & (1L << i)) != 0) {
                    Watch w = WATCHES.get(new Identity(world, DemandGroup.node(p.group(), i)));
                    if (w != null) {
                        w.absentEpoch = c.epoch;
                        resume(w);
                    }
                }
        }
    }

    private static void received(Context c) {
        RECEIVED.forEach(
                (id, p) -> {
                    if (!RECEIVED.remove(id, p) || p.epoch != c.epoch) return;
                    Group g = GROUPS.get(DemandGroup.of(id.node));
                    Watch w = WATCHES.get(id);
                    if (g == null || w == null || g.request != p.request) return;
                    w.serverChildren = p.children;
                    w.remoteVersion = p.version;
                    synchronized (w) {
                        w.localPresent = true;
                    }
                    resume(w);
                    g.nodeReceived(DemandGroup.slot(id.node), System.nanoTime());
                });
    }

    private static void cycle() {
        try {
            Context c = context;
            if (c == null) {
                long generation = GENERATION.get();
                if (workerGeneration == generation) return;
                workerGeneration = generation;
                GROUPS.values().forEach(g -> g.lease.invalidate());
                GROUPS.clear();
                ORDER.clear();
                REPLIES.clear();
                RECEIVED.clear();
                WATCHES.forEach(
                        (id, w) -> {
                            resume(w);
                            if (!id.world.isLive()) WATCHES.remove(id, w);
                        });
                return;
            }
            WorldEngine world = c.identifier.getOrCreateEngine();
            if (world == null || !world.isLive()) return;
            world.acquireRef();
            try {
                if (workerGeneration != c.generation) {
                    GROUPS.values().forEach(g -> g.lease.invalidate());
                    GROUPS.clear();
                    ORDER.clear();
                    WATCHES.values()
                            .forEach(
                                    w -> {
                                        w.hashedVersion = -1;
                                        w.audit = 0;
                                    });
                    workerGeneration = c.generation;
                    interestVersion = -1;
                }
                long interests = INTERESTS.get();
                if (interestVersion != interests
                        || centerX != c.x >> 5
                        || centerZ != c.z >> 5
                        || radius != c.radius) {
                    reconcile(c, world);
                    interestVersion = interests;
                    centerX = c.x >> 5;
                    centerZ = c.z >> 5;
                    radius = c.radius;
                }
                received(c);
                replies(c, world);
                long start = System.nanoTime();
                int probes = 0, sends = 0;
                int turns = Math.min(ORDER.size(), 32);
                for (int turn = 0;
                        turn < turns && System.nanoTime() - start < 20_000_000L;
                        turn++) {
                    long key = ORDER.removeFirst();
                    Group g = GROUPS.get(key);
                    if (g == null) continue;
                    ORDER.addLast(key);
                    long now = System.nanoTime();
                    for (int i = 0; i < 64; i++)
                        if ((g.wanted & (1L << i)) != 0) {
                            Watch w = WATCHES.get(new Identity(world, DemandGroup.node(key, i)));
                            if (w != null
                                    && w.hashedVersion != w.version.get()
                                    && w.remoteVersion != w.version.get()) {
                                if (g.request == 0) g.due = Math.min(g.due, now + 500_000_000L);
                                else g.dirty = true;
                            }
                        }
                    if (g.request != 0 && g.phase == WAIT_DATA) {
                        g.expireData(now);
                        continue;
                    }
                    if (g.request == 0) {
                        if (now < g.due) continue;
                        if (!g.building) {
                            g.lease.invalidate();
                            g.building = true;
                            g.audit = ++audits;
                            g.dirty = false;
                            g.beginLocalProof();
                        }
                        boolean complete = true;
                        for (int i = 0; i < 64; i++)
                            if ((g.wanted & (1L << i)) != 0) {
                                Watch w =
                                        WATCHES.get(new Identity(world, DemandGroup.node(key, i)));
                                if (w == null) {
                                    complete = false;
                                    continue;
                                }
                                if (w.audit != g.audit || w.hashedVersion != w.version.get()) {
                                    if (probes >= 64 || System.nanoTime() - start >= 20_000_000L) {
                                        complete = false;
                                        break;
                                    }
                                    probes++;
                                    long before = w.version.get();
                                    var section = SectionDataAccess.acquireStored(world, w.node);
                                    long hash = 0;
                                    boolean present = section != null;
                                    if (section != null) {
                                        try {
                                            var snapshot =
                                                    SectionDataAccess.snapshot(world, section);
                                            hash =
                                                    NativeNodeCodec.proofHash(
                                                            SemanticPalette.hashStored(
                                                                    snapshot.data(),
                                                                    world.getMapper(),
                                                                    c.biomes),
                                                            (byte)
                                                                    (snapshot.children()
                                                                            & w.serverChildren));
                                        } finally {
                                            section.release();
                                        }
                                    }
                                    if (w.version.get() != before) {
                                        complete = false;
                                        continue;
                                    }
                                    w.hash = hash;
                                    w.hashedVersion = before;
                                    w.audit = g.audit;
                                    synchronized (w) {
                                        w.localPresent = present;
                                    }
                                    if (present) resume(w);
                                }
                                g.hashes[i] = w.hash;
                                g.observeLocalPresence(i, w.localPresent);
                            }
                        if (!complete) continue;
                        g.begin(c.epoch, IDS.incrementAndGet());
                        g.building = false;
                    }
                    if (sends >= 1 || !g.takeAttempt(now))
                        continue; // <=20 requests/sec, distributed over groups.
                    long coverage =
                            (g.phase == LODDemandPayload.SUMMARY ? g.localCoverage : g.coverage)
                                    & g.wanted;
                    send(
                            c,
                            new LODDemandPayload(
                                    c.dimension,
                                    c.epoch,
                                    g.request,
                                    g.phase,
                                    key,
                                    g.wanted,
                                    coverage,
                                    g.revision,
                                    DemandGroup.digest(coverage, g.hashes),
                                    g.phase == LODDemandPayload.DETAIL ? g.hashes : new long[0]));
                    sends++;
                }
            } finally {
                world.releaseRef();
            }
        } catch (Throwable error) {
            me.cortex.voxy.common.Logger.error("client demand worker failed", error);
        }
    }
}
