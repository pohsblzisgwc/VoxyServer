package com.dripps.voxyserver;

import com.dripps.voxyserver.network.*;
import com.dripps.voxyserver.sync.version.*;

import io.netty.buffer.Unpooled;

import me.cortex.voxy.common.world.WorldEngine;

import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;

import java.nio.file.*;
import java.util.*;

public final class VersionSyncTest {
    private static final Identifier DIM = Identifier.parse("minecraft:overworld");

    public static void runAllTests() throws Exception {
        geometry();
        pages();
        wire();
        admissionAndReceipts();
        reconciliation();
        persistence();
        System.out.println(
                "PASS VersionSyncTest: fixed-order versions, full scope/partial borders,"
                    + " duplicate/out-of-order/truncated pages, int32 persistence, rollback"
                    + " authority, busy admission and stale/unsent/duplicate ACK recovery");
    }

    private static void geometry() {
        net.minecraft.world.level.LevelHeightAccessor bounds =
                new net.minecraft.world.level.LevelHeightAccessor() {
                    public int getHeight() {
                        return 384;
                    }

                    public int getMinY() {
                        return -64;
                    }
                };
        require(
                bounds.getMaxY() == 319
                        && bounds.getMaxSectionY() == 19
                        && bounds.getMinY() + bounds.getHeight() == 320,
                "Minecraft inclusive upper height assumption changed");
        require(
                new VersionScope(0, 0, 512, bounds.getMinY(), bounds.getMinY() + bounds.getHeight())
                        .contains(WorldEngine.getWorldSectionId(0, 0, 9, 0)),
                "highest LOD0 layer omitted");
        for (int radius : new int[] {512, 1024}) {
            var scope = new VersionScope(-31, 63, radius, -64, 320);
            var seen = new HashSet<Long>();
            for (int i = 0; i < scope.rootCount(); i++) {
                long root = scope.rootAt(i);
                require(
                        seen.add(root) && scope.rootIndex(root) == i,
                        "root order/negative coordinates");
            }
            long key = WorldEngine.getWorldSectionId(0, -1, 2, 1);
            require(scope.contains(key), "in-range LOD0 excluded");
            require(
                    !scope.contains(WorldEngine.getWorldSectionId(4, 0, 0, 0)),
                    "coarse node admitted");
            require(
                    !scope.contains(WorldEngine.getWorldSectionId(0, 0, 10, 0)),
                    "above dimension accepted");
        }
        var order = new VersionScope(0, 0, 1024, -64, 320, 64);
        require(
                order.compareDelivery(
                                WorldEngine.getWorldSectionId(0, 0, 1, 0),
                                WorldEngine.getWorldSectionId(0, 0, 0, 0))
                        < 0,
                "underground slab precedes visible surface");
        require(
                order.compareDelivery(
                                WorldEngine.getWorldSectionId(0, 0, 2, 0),
                                WorldEngine.getWorldSectionId(0, 100, 0, 100))
                        < 0,
                "far underground data precedes nearby surface");
        int[] a = new int[4096], b = new int[4096];
        a[1] = 7;
        b[2] = 7;
        require(
                VersionDigest.compute(a, null, 0) != VersionDigest.compute(b, null, 0),
                "version order ignored");
        b[2] = 0;
        b[1] = 7;
        require(
                VersionDigest.compute(a, null, 0) == VersionDigest.compute(b, null, 0),
                "sequence depends on insertion order");
        a[1] = Integer.MAX_VALUE;
        require(VersionDigest.compute(a, null, 0) != 0, "maximum positive int lost");
    }

    private static void pages() {
        var scope = new VersionScope(0, 0, 1024, -64, 320);
        var assembly = new SummaryAssembly(scope);
        int total = (scope.rootCount() + 255) / 256;
        expect(() -> assembly.digest(0));
        for (int p = total - 1; p >= 0; p--) {
            long[] h = new long[Math.min(256, scope.rootCount() - p * 256)];
            Arrays.fill(h, p + 1);
            assembly.add(p, total, h);
            assembly.add(p, total, h);
        }
        require(assembly.complete() && assembly.digest(0) == 1, "out-of-order summary incomplete");
        long[] conflict = new long[256];
        expect(() -> assembly.add(0, total, conflict));
        long root = VersionScope.root(WorldEngine.getWorldSectionId(0, 0, 0, 0));
        int[] values = new int[4096];
        var keys = new ArrayList<Long>();
        for (int i = 0; i < 4096; i++)
            if (scope.contains(VersionScope.node(root, i))) {
                values[i] = i + 1;
                keys.add(VersionScope.node(root, i));
            }
        long digest = VersionDigest.compute(values, null, root);
        int pages = (keys.size() + 255) / 256;
        var tables = new VersionTables(scope);
        for (int page = pages - 1; page >= 0; page--) {
            int a = page * 256, b = Math.min(a + 256, keys.size());
            long[] k = new long[b - a];
            int[] v = new int[k.length];
            for (int j = 0; j < k.length; j++) {
                k[j] = keys.get(a + j);
                v[j] = values[VersionScope.slot(k[j])];
            }
            var p = new VersionTablePayload(1, 1, root, digest, page, pages, k, v);
            tables.add(p);
            tables.add(p);
            if (page > 0) expect(() -> tables.complete(1));
        }
        require(tables.complete(1).size() == keys.size(), "full root lost leaf versions");
        var outside = WorldEngine.getWorldSectionId(4, 200, 0, 0);
        expect(
                () ->
                        tables.add(
                                new VersionTablePayload(
                                        1, 1, outside, 0, 0, 1, new long[0], new int[0])));
    }

    private static <T> T roundTrip(StreamCodec<RegistryFriendlyByteBuf, T> codec, T value) {
        var raw = Unpooled.buffer();
        try {
            var b = new RegistryFriendlyByteBuf(raw, RegistryAccess.EMPTY);
            codec.encode(b, value);
            T out = codec.decode(b);
            require(b.readableBytes() == 0, "wire trailing bytes");
            return out;
        } finally {
            raw.release();
        }
    }

    private static void wire() {
        var scope = new VersionScope(0, 0, 512, -64, 320);
        int pages = (scope.rootCount() + 255) / 256;
        long[] hashes = new long[256];
        hashes[0] = 42;
        var a =
                roundTrip(
                        VersionSummaryPayload.CODEC,
                        new VersionSummaryPayload(DIM, 7, 9, scope, 0, pages, hashes));
        require(a.digests()[0] == 42 && a.scope().equals(scope), "summary wire changed");
        long key = WorldEngine.getWorldSectionId(0, 0, 0, 0), root = VersionScope.root(key);
        var t =
                roundTrip(
                        VersionTablePayload.CODEC,
                        new VersionTablePayload(
                                7,
                                9,
                                root,
                                123,
                                0,
                                1,
                                new long[] {key},
                                new int[] {Integer.MAX_VALUE}));
        require(t.versions()[0] == Integer.MAX_VALUE, "version was narrowed");
        roundTrip(VersionBeginPayload.CODEC, new VersionBeginPayload(7, 9, 512));
        var frozen =
                roundTrip(VersionScopePayload.CODEC, new VersionScopePayload(7, 9, DIM, scope));
        require(frozen.scope().equals(scope), "range authorization changed snapshot");
        var s =
                roundTrip(
                        VersionSelectPayload.CODEC,
                        new VersionSelectPayload(7, 9, 0, 1, new long[] {key}, new int[] {1}));
        require(s.keys()[0] == key, "selection changed");
        roundTrip(VersionCancelPayload.CODEC, new VersionCancelPayload(7, 9));
        roundTrip(
                VersionSessionPayload.CODEC,
                new VersionSessionPayload(DIM, 7, UUID.randomUUID(), UUID.randomUUID()));
        expect(
                () ->
                        new VersionSelectPayload(
                                7,
                                9,
                                0,
                                1,
                                new long[] {WorldEngine.getWorldSectionId(4, 0, 0, 0)},
                                new int[] {1}));
        expect(() -> new VersionSelectPayload(7, 9, 0, 1, new long[] {key}, new int[] {0}));
        expect(() -> new VersionSelectPayload(7, 9, 0, 1, new long[] {key, key}, new int[] {1, 1}));
        var raw = Unpooled.buffer();
        try {
            var b = new RegistryFriendlyByteBuf(raw, RegistryAccess.EMPTY);
            VersionSelectPayload.CODEC.encode(b, s);
            raw.writerIndex(raw.writerIndex() - 1);
            expect(() -> VersionSelectPayload.CODEC.decode(b));
        } finally {
            raw.release();
        }
        short[] indices = new short[32768];
        var node =
                new VersionedNodePayload(
                        key,
                        Integer.MAX_VALUE,
                        new int[] {1},
                        new int[] {0},
                        new byte[] {15},
                        indices,
                        (byte) 255);
        byte[] encoded = VersionedNodePayload.encodeBatch(List.of(node), RegistryAccess.EMPTY);
        var decoded = VersionedNodePayload.decodeBatch(encoded, RegistryAccess.EMPTY).getFirst();
        require(
                decoded.version() == Integer.MAX_VALUE && decoded.indices().length == 32768,
                "LOD0 wire lost data/version");
        expect(
                () ->
                        VersionedNodePayload.decodeBatch(
                                Arrays.copyOf(encoded, encoded.length - 1), RegistryAccess.EMPTY));
        for (int size : new int[] {2, 3, 17, 257, 32768}) {
            int[] states = new int[size], biomes = new int[size];
            byte[] light = new byte[size];
            short[] ix = new short[32768];
            for (int i = 0; i < size; i++) {
                states[i] = i;
                biomes[i] = i * 3;
                light[i] = (byte) i;
            }
            for (int i = 0; i < ix.length; i++) ix[i] = (short) ((i * 73) % size);
            var mixed = new VersionedNodePayload(key, 123, states, biomes, light, ix, (byte) 255);
            var roundtrip =
                    VersionedNodePayload.decodeBatch(
                                    VersionedNodePayload.encodeBatch(
                                            List.of(mixed), RegistryAccess.EMPTY),
                                    RegistryAccess.EMPTY)
                            .getFirst();
            require(
                    Arrays.equals(ix, roundtrip.indices())
                            && Arrays.equals(states, roundtrip.states())
                            && Arrays.equals(biomes, roundtrip.biomes())
                            && Arrays.equals(light, roundtrip.light()),
                    "packed native node data corrupted at palette size " + size);
        }

        expect(
                () ->
                        new VersionedNodePayload(
                                WorldEngine.getWorldSectionId(4, 0, 0, 0),
                                1,
                                new int[] {1},
                                new int[] {0},
                                new byte[] {15},
                                indices,
                                (byte) 1));
        byte[] bad = encoded.clone();
        bad[0] = 17;
        expect(() -> VersionedNodePayload.decodeBatch(bad, RegistryAccess.EMPTY));
    }

    private static void admissionAndReceipts() {
        var gate = new RoundAdmission();
        require(gate.begin(1) == RoundAdmission.Result.NEW, "new round rejected");
        for (int i = 2; i < 10000; i++)
            require(
                    gate.begin(i) == RoundAdmission.Result.BUSY && gate.active() == 1,
                    "busy request replaced active work");
        require(gate.begin(1) == RoundAdmission.Result.REPLAY, "same round replay rejected");
        require(!gate.finish(2) && gate.active() == 1, "stale completion removed active round");
        var receipt = new BatchReceipt(7, 1);
        require(
                !receipt.acknowledge(7, 1) && !receipt.expire(999999, 600),
                "queued unsent batch was acknowledged/timed out");
        receipt.sent(100);
        require(!receipt.expire(699, 600), "early timeout");
        require(
                !receipt.acknowledge(8, 1) && !receipt.acknowledge(7, 2),
                "foreign batch acknowledged");
        require(
                receipt.acknowledge(7, 1) && !receipt.acknowledge(7, 1),
                "duplicate ACK released twice");
        var failed = new BatchReceipt(7, 2);
        failed.sent(0);
        require(failed.expire(600, 600) && !failed.acknowledge(7, 2), "expired batch revived");
        require(
                gate.finish(1) && gate.begin(2) == RoundAdmission.Result.NEW,
                "completed round blocked next round");
        require(gate.begin(1) == RoundAdmission.Result.BUSY, "old request replaced new delivery");
        gate.finish(2);
        require(gate.begin(1) == RoundAdmission.Result.STALE, "old round revived");
    }

    private static void reconciliation() {
        var scope = new VersionScope(0, 0, 8, -64, 320);
        long a = WorldEngine.getWorldSectionId(0, 0, 2, 0),
                b = WorldEngine.getWorldSectionId(0, 1, 2, 0),
                outside = WorldEngine.getWorldSectionId(0, 15, 2, 0);
        long root = VersionScope.root(a);
        var local = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
        local.put(a, 1);
        local.put(b, 4);
        local.put(outside, 9);
        var remote = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
        remote.put(a, 2);
        var plan = VersionReconciliation.compare(scope, Set.of(root), local, local::get, remote);
        require(
                plan.requested().get(a) == 2
                        && plan.unconfirmed().contains(b)
                        && !plan.unconfirmed().contains(outside),
                "updated/deleted/outside versions reconciled incorrectly");
        remote.put(a, 1);
        local.put(a, 5);
        plan = VersionReconciliation.compare(scope, Set.of(root), local, local::get, remote);
        require(
                plan.unconfirmed().contains(a) && plan.requested().get(a) == 1,
                "rollback remained confirmed");
        local.put(a, 1);
        var changed = local.clone();
        changed.remove(a);
        plan = VersionReconciliation.compare(scope, Set.of(root), local, changed::get, remote);
        require(
                plan.requested().get(a) == 1,
                "local native edits preserved stale server confirmation");
        var queue = new com.dripps.voxyserver.sync.DirtyRegionQueue<String>(0, 0, 0);
        queue.updateTiming(0, 30_000_000_000L, 30_000_000_000L);
        queue.mark("world", 0, 64, 0, 0);
        require(queue.poll(0) != null, "initial dirty work lost");
        for (int i = 0; i < 100000; i++) queue.mark("world", 0, 64, 0, 1);
        require(
                queue.dirtyCells() == 1
                        && queue.poll(29_999_999_999L) == null
                        && queue.poll(60_000_000_001L) != null,
                "redstone update interval escaped 30-60 seconds");
    }

    private static void persistence() throws Exception {
        Path dir = Files.createTempDirectory("voxy-int32-versions-");
        Path path = dir.resolve("versions.bin");
        try {
            var index = new VersionIndex(path);
            long key = WorldEngine.getWorldSectionId(0, 0, 0, 0);
            index.bootstrap(DIM, c -> c.accept(key));
            require(index.get(DIM, key) == 1, "bootstrap version");
            UUID ns = index.namespace();
            index.beginBatch();
            for (int i = 0; i < 100000; i++) index.changed(DIM, key);
            index.endBatch();
            require(
                    index.get(DIM, key) == 2 && index.increments() == 1,
                    "redstone batch incremented per event");
            var scope = new VersionScope(0, 0, 512, -64, 320);
            long root = VersionScope.root(key), first = index.digest(DIM, root, scope);
            require(index.digest(DIM, root, scope) == first, "cached digest changed");
            index.save();
            index.changed(DIM, key);
            require(index.digest(DIM, root, scope) != first, "changed version retained digest");
            index.save();
            require(
                    Files.exists(path.resolveSibling("versions.bin.journal")),
                    "incremental version journal missing");
            index.markClean();
            var clean = new VersionIndex(path);
            require(
                    clean.namespace().equals(ns) && clean.get(DIM, key) == 3,
                    "clean restart lost persisted version");
            // Simulate a crash: save without the post-native-shutdown clean marker.
            clean.changed(DIM, key);
            clean.save();
            var crash = new VersionIndex(path);
            require(
                    !crash.namespace().equals(ns) && crash.get(DIM, key) == 0,
                    "unclean data/metadata pair trusted");
            crash.bootstrap(DIM, c -> c.accept(key));
            require(crash.get(DIM, key) == 1, "crash recovery did not seed available LOD0 keys");
        } finally {
            try (var paths = Files.walk(dir)) {
                for (var p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
    }

    private static void expect(Runnable action) {
        try {
            action.run();
            throw new AssertionError("invalid operation accepted");
        } catch (IllegalArgumentException
                | IllegalStateException
                | IndexOutOfBoundsException expected) {
        }
    }

    private static void require(boolean value, String why) {
        if (!value) throw new AssertionError(why);
    }
}
