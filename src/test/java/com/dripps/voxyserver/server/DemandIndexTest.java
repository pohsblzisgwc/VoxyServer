package com.dripps.voxyserver.server;

import com.dripps.voxyserver.network.LODSectionPayload;
import com.dripps.voxyserver.sync.DemandGroup;

import me.cortex.voxy.common.world.WorldEngine;

import net.minecraft.resources.Identifier;

import java.util.concurrent.atomic.AtomicInteger;

public final class DemandIndexTest {
    private static final Identifier DIM = Identifier.parse("minecraft:overworld");

    private static LODSectionPayload data(long node, long hash) {
        return new LODSectionPayload(
                DIM, node, new int[] {0}, new int[] {0}, new byte[] {0}, new short[] {0}, hash);
    }

    public static void runAllTests() {
        var index = new DemandIndex();
        long group = DemandGroup.of(0),
                a = DemandGroup.node(group, 0),
                b = DemandGroup.node(group, 1);
        for (int i = 0; i < 100_000; i++)
            index.invalidate(DIM, WorldEngine.getWorldSectionId(0, i, 0, 0));
        require(
                index.size() == 0 && index.pending() == 0,
                "cold edits created an index/world scan");
        index.request(DIM, a, null, null);
        index.request(DIM, a, null, null);
        require(
                index.pending() == 1 && index.proof(DIM, group, 3) == null,
                "partial proof published");
        var calls = new AtomicInteger();
        index.computeOne(
                p -> {
                    calls.incrementAndGet();
                    return data(p.key().node(), 11);
                });
        index.request(DIM, b, null, null);
        index.computeOne(p -> null);
        var first = index.proof(DIM, group, 3);
        require(
                first != null && first.coverage() == 1 && first.hashes()[0] == 11,
                "presence proof fabricated absent node");
        require(
                index.payload(new DemandIndex.Key(DIM, b)) == null,
                "absence retained a replacement payload");
        index.invalidate(DIM, a);
        require(!index.current(DIM, group, 3, first.revision()), "dirty proof stayed valid");
        index.request(DIM, a, null, null);
        index.computeOne(
                p -> {
                    index.invalidate(DIM, a);
                    return data(a, 22);
                });
        require(
                index.get(DIM, a) == null && index.pending() == 1,
                "racing invalidation published stale content");
        index.computeOne(p -> data(a, 33));
        var fresh = index.proof(DIM, group, 3);
        require(
                fresh != null && fresh.revision() != first.revision() && fresh.hashes()[0] == 33,
                "new revision lost H2");
        index.request(DIM, a, null, null);
        try {
            index.computeOne(
                    p -> {
                        throw new IllegalStateException("read failure");
                    });
            throw new AssertionError("read failure swallowed");
        } catch (IllegalStateException expected) {
        }
        require(index.pending() == 1, "failed read lost recoverable work");
        index.computeOne(p -> data(a, 33));
        index.clear();
        for (int i = 0; i < 8192; i++)
            require(
                    index.request(DIM, WorldEngine.getWorldSectionId(0, i, 0, 0), null, null),
                    "probe admission failed");
        require(
                !index.request(DIM, WorldEngine.getWorldSectionId(0, 8192, 0, 0), null, null)
                        && index.pending() == 8192,
                "probe memory cap ignored");
        index.clear();
        require(index.pending() == 0 && index.size() == 0, "index cleanup leaked work");
        System.out.println(
                "PASS DemandIndexTest: shared proof, absent data, cold edits, invalidation race,"
                        + " failed reads and probe cap");
    }

    public static void runNativeBudgetTests(
            WorldEngine world,
            net.minecraft.core.Registry<net.minecraft.world.level.biome.Biome> biomes,
            long present) {
        var index = new DemandIndex();
        var permits = new AtomicInteger();
        var hashes = new AtomicInteger();
        long absent = WorldEngine.getWorldSectionId(4, 25, 0, 25);
        index.request(DIM, absent, null, biomes);
        require(
                index.computeOne(
                        p ->
                                DemandIndex.loadNode(
                                        world,
                                        p,
                                        () -> {
                                            permits.incrementAndGet();
                                            return false;
                                        },
                                        elapsed -> hashes.incrementAndGet())),
                "absent probe blocked on hash budget");
        require(
                permits.get() == 0 && hashes.get() == 0 && !index.get(DIM, absent).present(),
                "absence consumed semantic hashing allowance");
        index.request(DIM, present, null, biomes);
        require(
                !index.computeOne(
                                p ->
                                        DemandIndex.loadNode(
                                                world,
                                                p,
                                                () -> false,
                                                elapsed -> hashes.incrementAndGet()))
                        && index.pending() == 1
                        && index.get(DIM, present) == null,
                "hash deferral published absence or lost work");
        require(
                index.computeOne(
                                p ->
                                        DemandIndex.loadNode(
                                                world,
                                                p,
                                                () -> true,
                                                elapsed -> hashes.incrementAndGet()))
                        && hashes.get() == 1
                        && index.get(DIM, present).present(),
                "budget recovery failed");
        index.invalidate(DIM, absent);
        require(index.get(DIM, absent) == null, "negative proof survived source invalidation");
        System.out.println(
                "PASS native demand budget: negative probes bypass hash limit, deferred present"
                    + " nodes resume without fabricated absence");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
