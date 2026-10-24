package com.dripps.voxyserver.client;

import com.dripps.voxyserver.network.*;
import com.dripps.voxyserver.sync.SectionDataAccess;
import com.dripps.voxyserver.sync.version.*;

import it.unimi.dsi.fastutil.longs.*;

import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.WorldIdentifier;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Enumerates the client's stored LOD0 metadata, never the server's view grid or voxel contents. */
public final class ClientDemand {
    private record Context(
            long generation,
            VersionSessionPayload session,
            WorldIdentifier world,
            int x,
            int z,
            int radius,
            int minY,
            int maxY) {}

    private static final ScheduledExecutorService WORKER =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        var t = new Thread(r, "VoxyServer Client Demand");
                        t.setDaemon(true);
                        return t;
                    });
    private static final AtomicLong GENERATION = new AtomicLong(), IDS = new AtomicLong();
    private static volatile Context context;
    private static VersionSessionPayload pendingSession;
    private static final ArrayBlockingQueue<CustomPacketPayload> replies =
            new ArrayBlockingQueue<>(512);
    private static final AtomicBoolean overflow = new AtomicBoolean();
    private static final Object LOCAL_LOCK = new Object();
    private static WorldEngine activeWorld;
    private static ClientVersionStore store;
    private static final LinkedHashMap<WorldEngine, ClientVersionStore> stores =
            new LinkedHashMap<>();
    private static final LongOpenHashSet pendingChanges = new LongOpenHashSet();
    private static boolean invalidateAll;
    private static long workerGeneration = -1, nextAudit, lastSave, completed, failed, lastReport;
    private static volatile Round round;
    private static long pendingRound, pendingSince;
    private static int pendingAttempts, pendingRadius;

    private static final class Round {
        final long id;
        final VersionScope scope;
        final long[] digests;
        final Long2IntOpenHashMap local;
        final VersionTables tables;
        long lastActivity = System.nanoTime();
        int retries, page, pages;
        long[] keys;
        int[] versions;
        Long2IntOpenHashMap pageAllowed = new Long2IntOpenHashMap();
        boolean transferring;

        Round(long id, VersionScope scope, long[] digests, Long2IntOpenHashMap local) {
            this.id = id;
            this.scope = scope;
            this.digests = digests;
            this.local = local;
            tables = new VersionTables(scope);
        }
    }

    static {
        SectionDataAccess.observer =
                (world, key) -> {
                    if (WorldEngine.getLevel(key) != 0) return;
                    synchronized (LOCAL_LOCK) {
                        var known = stores.get(world);
                        if (known != null) known.invalidate(key);
                        if (world == activeWorld && store == null) {
                            if (pendingChanges.size() < 131072) pendingChanges.add(key);
                            else invalidateAll = true;
                        }
                    }
                };
        WORKER.scheduleWithFixedDelay(ClientDemand::cycle, 25, 50, TimeUnit.MILLISECONDS);
    }

    private ClientDemand() {}

    public static void start(VersionSessionPayload p) {
        reset();
        pendingSession = p;
        activate();
    }

    private static void activate() {
        var p = pendingSession;
        var mc = Minecraft.getInstance();
        if (p == null
                || !ClientLodSettings.isProtocolOk()
                || !ClientLodSettings.isEnabled()
                || mc.level == null
                || mc.player == null
                || !p.dimension().equals(mc.level.dimension().identifier())) return;
        var id = WorldIdentifier.of(mc.level);
        if (id == null) return;
        pendingSession = null;
        context =
                new Context(
                        GENERATION.incrementAndGet(),
                        p,
                        id,
                        mc.player.getBlockX(),
                        mc.player.getBlockZ(),
                        effectiveRadius(),
                        mc.level.getMinY(),
                        mc.level.getMinY() + mc.level.getHeight());
        com.dripps.voxyserver.Voxyserver.LOGGER.info(
                "version LOD session active: dimension={} epoch={} radiusChunks={}",
                p.dimension(),
                p.epoch(),
                effectiveRadius());
    }

    private static int effectiveRadius() {
        int preferred = ClientLodSettings.getPreferredRadius(),
                max = ClientLodSettings.getServerMaxRadius();
        return Math.max(1, preferred <= 0 ? max : Math.min(preferred, max));
    }

    public static void tick() {
        if (pendingSession != null) activate();
        Context c = context;
        if (c == null) return;
        var mc = Minecraft.getInstance();
        if (mc.level == null
                || mc.player == null
                || !ClientLodSettings.isEnabled()
                || !ClientLodSettings.isProtocolOk()) {
            reset();
            return;
        }
        if (!mc.level.dimension().identifier().equals(c.session.dimension())) return;
        context =
                new Context(
                        c.generation,
                        c.session,
                        c.world,
                        mc.player.getBlockX(),
                        mc.player.getBlockZ(),
                        effectiveRadius(),
                        c.minY,
                        c.maxY);
    }

    public static void reset() {
        synchronized (LOCAL_LOCK) {
            pendingSession = null;
            context = null;
            GENERATION.incrementAndGet();
        }
    }

    public static void prepareNativeShutdown() {
        reset();
        try {
            CompletableFuture.runAsync(() -> {}, WORKER).join();
        } catch (RuntimeException e) {
            com.dripps.voxyserver.Voxyserver.LOGGER.warn(
                    "client version shutdown barrier failed", e);
        }
    }

    public static void nativeClosed() {
        WORKER.execute(
                () -> {
                    synchronized (LOCAL_LOCK) {
                        for (var state : stores.values())
                            try {
                                state.markClean();
                            } catch (java.io.IOException e) {
                                com.dripps.voxyserver.Voxyserver.LOGGER.warn(
                                        "client version checkpoint failed", e);
                            }
                        stores.clear();
                        store = null;
                        activeWorld = null;
                        pendingChanges.clear();
                    }
                });
    }

    public static void receive(CustomPacketPayload p) {
        if (!replies.offer(p)) overflow.set(true);
    }

    private static boolean current(Context c) {
        var now = context;
        return now != null && now.generation == c.generation;
    }

    private static void send(Context c, CustomPacketPayload p) {
        Minecraft.getInstance()
                .execute(
                        () -> {
                            if (current(c) && Minecraft.getInstance().getConnection() != null)
                                ClientPlayNetworking.send(p);
                        });
    }

    private static void summary(Context c, Round r) {
        int pages = (r.digests.length + 255) / 256;
        // Pages share one captured scope and round ID. No token bucket or request/second throttle.
        Minecraft.getInstance()
                .execute(
                        () -> {
                            if (!current(c) || Minecraft.getInstance().getConnection() == null)
                                return;
                            for (int page = 0; page < pages; page++) {
                                if (!current(c)) return;
                                int a = page * 256;
                                ClientPlayNetworking.send(
                                        new VersionSummaryPayload(
                                                c.session.dimension(),
                                                c.session.epoch(),
                                                r.id,
                                                r.scope,
                                                page,
                                                pages,
                                                Arrays.copyOfRange(
                                                        r.digests,
                                                        a,
                                                        Math.min(a + 256, r.digests.length))));
                            }
                        });
        r.lastActivity = System.nanoTime();
    }

    private static void build(Context c, WorldEngine world, VersionScopePayload grant) {
        var scope = grant.scope();
        Long2IntOpenHashMap known;
        synchronized (LOCAL_LOCK) {
            known = store.snapshot();
        }
        var local = new Long2IntOpenHashMap();
        var roots = new Long2ObjectOpenHashMap<int[]>();
        // Storage keys only. Independently cached terrain without a server version remains
        // unknown=0.
        world.storage.iteratePositions(
                0,
                key -> {
                    if (!current(c)) throw new CancellationException("replaced version session");
                    if (!scope.contains(key)) return;
                    int version = known.get(key);
                    if (version == 0) return;
                    local.put(key, version);
                    roots.computeIfAbsent(VersionScope.root(key), k -> new int[4096])[
                                    VersionScope.slot(key)] =
                            version;
                });
        long[] digests = new long[scope.rootCount()];
        for (var e : roots.long2ObjectEntrySet()) {
            int i = scope.rootIndex(e.getLongKey());
            if (i >= 0) digests[i] = VersionDigest.compute(e.getValue(), scope, e.getLongKey());
        }
        if (!current(c)) return;
        var r = new Round(grant.round(), scope, digests, local);
        synchronized (LOCAL_LOCK) {
            if (!current(c) || pendingRound != grant.round()) return;
            round = r;
            pendingRound = 0;
        }
        summary(c, r);
    }

    private static void select(Context c, Round r) {
        int a = r.page * 256, b = Math.min(a + 256, r.keys.length);
        r.pageAllowed.clear();
        for (int i = a; i < b; i++) r.pageAllowed.put(r.keys[i], r.versions[i]);
        send(
                c,
                new VersionSelectPayload(
                        c.session.epoch(),
                        r.id,
                        r.page,
                        r.pages,
                        Arrays.copyOfRange(r.keys, a, b),
                        Arrays.copyOfRange(r.versions, a, b)));
        r.lastActivity = System.nanoTime();
    }

    private static void handle(Context c, CustomPacketPayload p) {
        Round r = round;
        if (r == null) {
            if (p instanceof VersionStatusPayload status
                    && status.epoch() == c.session.epoch()
                    && status.round() == pendingRound) {
                pendingRound = 0;
                failed++;
                nextAudit =
                        System.nanoTime()
                                + ThreadLocalRandom.current().nextLong(90, 151) * 1_000_000_000L;
            }
            return;
        }
        if (p instanceof VersionTablePayload table) {
            if (table.epoch() != c.session.epoch() || table.round() != r.id || r.transferring)
                return;
            r.tables.add(table);
            r.lastActivity = System.nanoTime();
            return;
        }
        if (!(p instanceof VersionStatusPayload status)
                || status.epoch() != c.session.epoch()
                || status.round() != r.id) return;
        r.lastActivity = System.nanoTime();
        switch (status.status()) {
            case VersionStatusPayload.STARTED -> {}
            case VersionStatusPayload.TABLES_COMPLETE -> {
                if (r.transferring) return;
                var remote = r.tables.complete(status.count());
                it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap wanted;
                synchronized (LOCAL_LOCK) {
                    var plan =
                            VersionReconciliation.compare(
                                    r.scope, r.tables.changedRoots(), r.local, store::get, remote);
                    for (long key : plan.unconfirmed()) store.invalidate(key);
                    wanted = plan.requested();
                }
                r.keys = wanted.keySet().toLongArray();
                it.unimi.dsi.fastutil.longs.LongArrays.quickSort(r.keys, r.scope::compareDelivery);
                r.versions = new int[r.keys.length];
                for (int i = 0; i < r.keys.length; i++) r.versions[i] = wanted.get(r.keys[i]);
                r.pages = Math.max(1, (r.keys.length + 255) / 256);
                r.transferring = true;
                select(c, r);
            }
            case VersionStatusPayload.PAGE_DONE -> {
                if (r.transferring && status.page() == r.page && r.page + 1 < r.pages) {
                    r.page++;
                    select(c, r);
                }
            }
            case VersionStatusPayload.DONE -> {
                if (r.transferring) {
                    round = null;
                    completed++;
                }
            }
            case VersionStatusPayload.BUSY,
                    VersionStatusPayload.FAILED,
                    VersionStatusPayload.STALE ->
                    abort(c, r, false);
            default -> throw new IllegalArgumentException("unknown version status");
        }
    }

    private static void abort(Context c, Round r, boolean cancel) {
        if (round != r) return;
        if (cancel) send(c, new VersionCancelPayload(c.session.epoch(), r.id));
        round = null;
        failed++;
        nextAudit =
                System.nanoTime() + ThreadLocalRandom.current().nextLong(90, 151) * 1_000_000_000L;
    }

    /**
     * Lease check and native installation share this monitor, including page completion/session
     * reset.
     */
    public static boolean install(
            PreSerializedLodPayload batch,
            VersionedNodePayload node,
            WorldEngine world,
            Runnable write) {
        synchronized (world) {
            synchronized (LOCAL_LOCK) {
                Context c = context;
                Round r = round;
                if (c == null
                        || r == null
                        || !r.transferring
                        || batch.streamEpoch() != c.session.epoch()
                        || batch.demandRequestId() != r.id
                        || world != activeWorld) return false;
                int expected = r.pageAllowed.get(node.key());
                if (expected == 0
                        || node.version() < expected
                        || store.get(node.key()) > node.version()) return false;
                write.run();
                store.record(node.key(), node.version());
                r.lastActivity = System.nanoTime();
                return true;
            }
        }
    }

    private static void saveStores() throws java.io.IOException {
        java.util.List<ClientVersionStore> copies;
        synchronized (LOCAL_LOCK) {
            copies = java.util.List.copyOf(stores.values());
        }
        for (var saved : copies) saved.save();
    }

    private static void cycle() {
        Context c = context;
        try {
            if (c == null) {
                if (workerGeneration == GENERATION.get()) return;
                workerGeneration = GENERATION.get();
                synchronized (LOCAL_LOCK) {
                    round = null;
                    pendingRound = 0;
                }
                saveStores();
                replies.clear();
                return;
            }
            var world = c.world.getOrCreateEngine();
            if (world == null || !world.isLive()) return;
            world.acquireRef();
            try {
                if (workerGeneration != c.generation) {
                    saveStores();
                    synchronized (LOCAL_LOCK) {
                        store = null;
                        activeWorld = world;
                        pendingChanges.clear();
                        invalidateAll = false;
                        round = null;
                        pendingRound = 0;
                    }
                    var loaded =
                            new ClientVersionStore(
                                    c.session.world()
                                            + "_"
                                            + c.session.versions()
                                            + "_"
                                            + c.world.getWorldId());
                    synchronized (LOCAL_LOCK) {
                        var existing = stores.get(world);
                        if (existing != null && existing.path().equals(loaded.path()))
                            loaded = existing;
                    }
                    final var loadedStore = loaded;
                    try {
                        if (!stores.containsValue(loadedStore)) loadedStore.load();
                    } catch (java.io.IOException e) {
                        com.dripps.voxyserver.Voxyserver.LOGGER.warn(
                                "client versions invalid; preserving terrain and requesting fresh"
                                    + " data: {}",
                                e.toString());
                    }
                    synchronized (LOCAL_LOCK) {
                        if (!current(c)) return;
                        if (invalidateAll)
                            for (long key : loadedStore.snapshot().keySet())
                                loadedStore.invalidate(key);
                        else for (long key : pendingChanges) loadedStore.invalidate(key);
                        pendingChanges.clear();
                        store = loadedStore;
                        stores.put(world, loadedStore);
                        while (stores.size() > 4) {
                            var it = stores.entrySet().iterator();
                            var old = it.next();
                            old.getValue().save();
                            it.remove();
                        }
                        workerGeneration = c.generation;
                        nextAudit = 0;
                    }
                    replies.clear();
                    overflow.set(false);
                }
                if (overflow.getAndSet(false)) {
                    if (round != null) abort(c, round, true);
                    else if (pendingRound != 0) {
                        send(c, new VersionCancelPayload(c.session.epoch(), pendingRound));
                        pendingRound = 0;
                        nextAudit = System.nanoTime() + 90_000_000_000L;
                    }
                }
                CustomPacketPayload reply;
                int n = 0;
                while (n++ < 512 && (reply = replies.poll()) != null) {
                    if (!current(c)) return;
                    if (reply instanceof VersionScopePayload grant) {
                        if (grant.epoch() == c.session.epoch()
                                && grant.round() == pendingRound
                                && grant.dimension().equals(c.session.dimension())) {
                            if (grant.scope().radiusChunks() > pendingRadius
                                    || grant.scope().minY() != c.minY
                                    || grant.scope().maxY() != c.maxY)
                                throw new IllegalArgumentException("server granted invalid scope");
                            build(c, world, grant);
                        }
                    } else
                        synchronized (LOCAL_LOCK) {
                            handle(c, reply);
                        }
                }
                long now = System.nanoTime();
                Round r = round;
                if (r == null && pendingRound == 0 && now >= nextAudit) {
                    pendingRound = IDS.incrementAndGet();
                    pendingSince = now;
                    pendingAttempts = 0;
                    pendingRadius = c.radius;
                    nextAudit =
                            now + ThreadLocalRandom.current().nextLong(90, 151) * 1_000_000_000L;
                    send(
                            c,
                            new VersionBeginPayload(
                                    c.session.epoch(), pendingRound, pendingRadius));
                } else if (r == null && pendingRound != 0 && now - pendingSince > 30_000_000_000L) {
                    if (++pendingAttempts <= 3) {
                        pendingSince = now;
                        send(
                                c,
                                new VersionBeginPayload(
                                        c.session.epoch(), pendingRound, pendingRadius));
                    } else {
                        send(c, new VersionCancelPayload(c.session.epoch(), pendingRound));
                        pendingRound = 0;
                        failed++;
                        nextAudit =
                                now
                                        + ThreadLocalRandom.current().nextLong(90, 151)
                                                * 1_000_000_000L;
                    }
                } else if (r != null && now - r.lastActivity > 30_000_000_000L) {
                    if (r.transferring) select(c, r);
                    else if (++r.retries <= 3) summary(c, r);
                    else abort(c, r, true);
                }
                if (now - lastSave > 30_000_000_000L) {
                    lastSave = now;
                    saveStores();
                }
                if (now - lastReport > 10_000_000_000L) {
                    lastReport = now;
                    com.dripps.voxyserver.Voxyserver.LOGGER.info(
                            "client version sync: activeRound={} phase={} completed={} failed={}",
                            round == null ? pendingRound : round.id,
                            round == null
                                    ? (pendingRound == 0 ? "idle" : "awaiting scope")
                                    : round.transferring ? "LOD0 delivery" : "version tables",
                            completed,
                            failed);
                }
            } finally {
                world.releaseRef();
            }
        } catch (CancellationException ignored) {
        } catch (Throwable e) {
            com.dripps.voxyserver.Voxyserver.LOGGER.error("client version sync failed", e);
            if (c != null) {
                if (round != null) abort(c, round, true);
                else if (pendingRound != 0) {
                    send(c, new VersionCancelPayload(c.session.epoch(), pendingRound));
                    pendingRound = 0;
                    failed++;
                    nextAudit =
                            System.nanoTime()
                                    + ThreadLocalRandom.current().nextLong(90, 151)
                                            * 1_000_000_000L;
                }
            }
        }
    }
}
