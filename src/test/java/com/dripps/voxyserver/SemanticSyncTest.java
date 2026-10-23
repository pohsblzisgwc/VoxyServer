package com.dripps.voxyserver;

import com.dripps.voxyserver.util.BoundedLruCache;
import com.dripps.voxyserver.util.HashWorkBudget;
import com.dripps.voxyserver.util.SemanticSectionHash;
import com.dripps.voxyserver.util.SessionEpoch;
import com.dripps.voxyserver.util.WorldIdentity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Random;

/** Runs against production synchronization primitives using only the JDK. */
public final class SemanticSyncTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception { runAllTests(); }

    public static void runAllTests() throws Exception {
        semanticEquivalence();
        semanticChanges();
        invalidIndices();
        globalBudget();
        boundedCaches();
        rollbackIdentity();
        System.out.println("PASS SemanticSyncTest: semantic equivalence, changes, validation, budgets, caches, rollback");
    }

    private static long token(String state, String biome, int light) {
        return SemanticSectionHash.paletteEntry(state, biome, light);
    }

    private static void semanticEquivalence() {
        var forward = new java.util.LinkedHashMap<String, String>();
        forward.put("waterlogged", "false"); forward.put("axis", "x");
        var reversed = new java.util.LinkedHashMap<String, String>();
        reversed.put("axis", "x"); reversed.put("waterlogged", "false");
        check(SemanticSectionHash.canonicalBlockState("example:beam", forward)
                        .equals(SemanticSectionHash.canonicalBlockState("example:beam", reversed)),
                "property insertion order must not affect semantic identity");
        long stone = token("minecraft:stone[]", "minecraft:plains", 15);
        long grass = token("minecraft:grass_block[snowy=false]", "minecraft:plains", 240);
        short[] original = new short[SemanticSectionHash.VOXELS];
        Random random = new Random(12563);
        for (int i = 0; i < original.length; i++) original[i] = (short) random.nextInt(2);
        long reference = SemanticSectionHash.compute(new long[]{stone, grass}, original);
        long[] storedServer = new long[original.length], storedClient = new long[original.length];
        for (int i = 0; i < original.length; i++) {
            storedServer[i] = original[i] == 0 ? 71 : 400;
            storedClient[i] = original[i] == 0 ? 90000 : 12;
        }
        check(reference == SemanticSectionHash.computeMapped(storedServer, id -> id == 71 ? stone : grass),
                "server mapper representation changed the semantic fingerprint");
        check(reference == SemanticSectionHash.computeMapped(storedClient, id -> id == 90000 ? stone : grass),
                "actual stored client data disagrees with wire semantics");

        // Different mapper/registry IDs are resolved to these same semantic tokens.
        // Duplicate and unused palette entries, and arbitrary LUT order, must have no effect.
        for (int repeat = 0; repeat < 100; repeat++) {
            long[] palette = {grass, stone, stone, token("unused:block[]", "unused:biome", repeat)};
            short[] remapped = original.clone();
            for (int i = 0; i < remapped.length; i++) {
                remapped[i] = original[i] == 1 ? 0 : (short) (random.nextBoolean() ? 1 : 2);
            }
            check(reference == SemanticSectionHash.compute(palette, remapped), "palette representation changed meaning");
        }
        check(reference != 0, "zero is reserved for unknown legacy content");
    }

    private static void semanticChanges() {
        short[] voxels = new short[SemanticSectionHash.VOXELS];
        long baseToken = token("minecraft:oak_log[axis=x]", "minecraft:plains", 15);
        long baseline = SemanticSectionHash.compute(new long[]{baseToken}, voxels);
        for (long different : new long[]{
                token("minecraft:oak_log[axis=y]", "minecraft:plains", 15),
                token("minecraft:oak_log[axis=x]", "minecraft:desert", 15),
                token("minecraft:oak_log[axis=x]", "minecraft:plains", 14)}) {
            check(baseline != SemanticSectionHash.compute(new long[]{different}, voxels), "semantic edit was ignored");
        }
        long other = token("minecraft:dirt[]", "minecraft:plains", 15);
        voxels[1] = 1;
        long left = SemanticSectionHash.compute(new long[]{baseToken, other}, voxels);
        voxels[1] = 0;
        voxels[2] = 1;
        check(left != SemanticSectionHash.compute(new long[]{baseToken, other}, voxels), "voxel position was ignored");
        check(token("ab", "c", 0) != token("a", "bc", 0), "semantic fields need unambiguous boundaries");
    }

    private static void invalidIndices() {
        short[] voxels = new short[SemanticSectionHash.VOXELS];
        voxels[voxels.length - 1] = (short) 65535;
        expectInvalid(() -> SemanticSectionHash.compute(new long[]{1}, voxels));
        expectInvalid(() -> SemanticSectionHash.compute(new long[]{1}, new short[4096]));
        expectInvalid(() -> token("x", "y", -1));
    }

    private static void globalBudget() {
        HashWorkBudget budget = new HashWorkBudget();
        check(budget.tryAcquire(100, 20, 2), "first player should get work");
        check(budget.tryAcquire(100, 20, 2), "second shared operation should fit");
        check(!budget.tryAcquire(101, 20, 2), "new player or tick must not reset the interval");
        check(!budget.tryAcquire(119, 20, 2), "interval reset too early");
        check(budget.tryAcquire(120, 20, 2, 10), "next interval should reopen");
        budget.recordNanos(11);
        check(!budget.tryAcquire(120, 20, 2, 10), "elapsed-time budget was ignored");
        check(budget.tryAcquire(0, 20, 2, 10), "tick rollback must safely reopen a new window");
        expectInvalid(() -> budget.tryAcquire(1, 0, 2));
    }

    private static void boundedCaches() {
        BoundedLruCache<Integer, String> cache = new BoundedLruCache<>(2);
        cache.put(1, "one"); cache.put(2, "two");
        check(cache.get(1).equals("one"), "cache miss");
        cache.put(3, "three");
        check(cache.get(2) == null && cache.size() == 2, "LRU eviction failed");
        cache.compute(4, (key, old) -> "four");
        check(cache.size() == 2 && cache.get(1) == null, "compute bypassed capacity");
        cache.remove(4);
        check(cache.size() == 1, "dirty invalidation failed");
    }

    private static void rollbackIdentity() throws Exception {
        Path directory = Files.createTempDirectory("voxy-sync-identity");
        try {
            Path identity = directory.resolve("world-id");
            var first = WorldIdentity.loadOrCreate(identity);
            byte[] backup = Files.readAllBytes(identity);
            check(first.equals(WorldIdentity.loadOrCreate(identity)), "ordinary restart changed namespace");
            var epochs = new HashSet<Long>();
            for (int i = 0; i < 1000; i++) {
                long epoch = SessionEpoch.next();
                check(epoch > 0 && epochs.add(epoch), "session epoch reused");
            }
            Files.write(identity, backup);
            check(first.equals(WorldIdentity.loadOrCreate(identity)), "restored world changed identity");
            check(epochs.add(SessionEpoch.next()), "restoring metadata reused a session epoch");
        } finally { Files.deleteIfExists(directory.resolve("world-id")); Files.delete(directory); }
    }

    private static void expectInvalid(Runnable action) {
        try { action.run(); throw new AssertionError("invalid input was accepted"); }
        catch (IllegalArgumentException expected) { }
    }
}
