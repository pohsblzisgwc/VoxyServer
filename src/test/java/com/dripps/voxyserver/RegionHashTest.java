package com.dripps.voxyserver;

import com.dripps.voxyserver.server.hash.RegionHashManager;
import com.dripps.voxyserver.util.FastHash;
import net.minecraft.resources.Identifier;

/**
 * Unit test suite for 64-bit Merkle-style hashing, stable coordinate keys,
 * hash lifecycle states, and dirty invalidation.
 */
public class RegionHashTest {

    public static void runAllTests() {
        System.out.println("--- Running RegionHashTest ---");
        testFastHashVoxelData();
        testFastHashCombineAndRoll();
        testStableRegionCoordinates();
        testRegionHashLifecycleAndDirtyInvalidation();
        testDimensionClearing();
        System.out.println("[PASS] RegionHashTest completed successfully.\n");
    }

    private static void testFastHashVoxelData() {
        assert FastHash.hashVoxelData(null) == 0L : "Null data must hash to 0L";
        assert FastHash.hashVoxelData(new long[0]) == 0L : "Empty data must hash to 0L";

        long[] dataA = new long[]{1L, 2L, 3L, 4L, 5L};
        long hashA1 = FastHash.hashVoxelData(dataA);
        long hashA2 = FastHash.hashVoxelData(dataA);
        assert hashA1 != 0L : "Hash must be non-zero for valid data";
        assert hashA1 == hashA2 : "Hash must be deterministic";

        long[] dataB = new long[]{1L, 2L, 3L, 4L, 6L}; // 1 bit difference
        long hashB = FastHash.hashVoxelData(dataB);
        assert hashA1 != hashB : "Different data must produce different hashes";
        System.out.println("  ✓ testFastHashVoxelData");
    }

    private static void testFastHashCombineAndRoll() {
        long parentBase = 0x1234567890ABCDEFL;
        long childKey = 42L;
        long childHash = 0xFEEDFACECAFEBABEL;

        // Folding into parent
        long folded = FastHash.combine(parentBase, childKey, childHash);
        assert folded != parentBase : "Folded hash must change base";

        // XOR rolling property: folding the exact same child out must restore original base
        long rolledBack = FastHash.combine(folded, childKey, childHash);
        assert rolledBack == parentBase : "XOR rolling property must restore original parent base";
        System.out.println("  ✓ testFastHashCombineAndRoll (Merkle XOR rollability verified)");
    }

    private static void testStableRegionCoordinates() {
        int regX = 15;
        int regZ = -8;

        long key = RegionHashManager.getRegionKey(regX, regZ);
        assert RegionHashManager.getRegionX(key) == regX : "Extracted regionX must equal 15";
        assert RegionHashManager.getRegionZ(key) == regZ : "Extracted regionZ must equal -8";

        // Test boundary values
        long minKey = RegionHashManager.getRegionKey(Integer.MIN_VALUE, Integer.MAX_VALUE);
        assert RegionHashManager.getRegionX(minKey) == Integer.MIN_VALUE : "Min region coordinate preserved";
        assert RegionHashManager.getRegionZ(minKey) == Integer.MAX_VALUE : "Max region coordinate preserved";
        System.out.println("  ✓ testStableRegionCoordinates");
    }

    private static void testRegionHashLifecycleAndDirtyInvalidation() {
        RegionHashManager manager = RegionHashManager.INSTANCE;
        Identifier dim = Identifier.parse("minecraft:overworld");
        long secKey = 9999L;
        long regKey = RegionHashManager.getRegionKeyForSection(secKey);

        // 1. Initial state: UNCOMPUTED
        assert manager.getRegionState(dim, regKey) == RegionHashManager.HashState.UNCOMPUTED :
                "Initial region state must be UNCOMPUTED";
        assert manager.getRegionHash(dim, regKey) == 0L : "Initial hash must be 0L";

        // 2. Update section data: state transitions to PARTIAL and produces non-zero hash
        long[] voxelData = new long[]{100L, 200L, 300L};
        manager.updateSectionHash(dim, secKey, 1234L);

        assert manager.getRegionState(dim, regKey) == RegionHashManager.HashState.PARTIAL :
                "Region state must become PARTIAL after section update";
        long currentHash = manager.getRegionHash(dim, regKey);
        assert currentHash == 0L : "Partial coverage must never be advertised as a complete region";

        // Client matches current hash -> isRegionSynced returns true
        assert !manager.isRegionSynced(dim, regKey, currentHash) :
                "Partial region cannot prove that a client has complete coverage";

        // 3. Mark section as dirty (block change): state transitions to DIRTY
        manager.invalidateSection(dim, secKey);
        assert manager.getRegionState(dim, regKey) == RegionHashManager.HashState.DIRTY :
                "Region state must transition to DIRTY on section invalidation";

        // When DIRTY, isRegionSynced MUST return false, forcing server to stream updated section!
        assert !manager.isRegionSynced(dim, regKey, currentHash) :
                "DIRTY region must never be treated as synced, even if client hash matches stale value";
        assert manager.getRegionHash(dim, regKey) == 0L :
                "DIRTY region getRegionHash must return 0L";
        System.out.println("  ✓ testRegionHashLifecycleAndDirtyInvalidation");
    }

    private static void testDimensionClearing() {
        RegionHashManager manager = RegionHashManager.INSTANCE;
        Identifier overworld = Identifier.parse("minecraft:overworld");
        Identifier nether = Identifier.parse("minecraft:the_nether");

        manager.updateSectionHash(overworld, 100L, 1L);
        manager.updateSectionHash(nether, 100L, 2L);

        long owRegKey = RegionHashManager.getRegionKeyForSection(100L);
        assert manager.getRegionState(overworld, owRegKey) == RegionHashManager.HashState.PARTIAL : "Overworld hash must exist";
        assert manager.getRegionState(nether, owRegKey) == RegionHashManager.HashState.PARTIAL : "Nether hash must exist";

        // Clear only overworld
        manager.clearDimension(overworld);
        assert manager.getRegionHash(overworld, owRegKey) == 0L : "Overworld hash must be cleared";
        assert manager.getRegionState(nether, owRegKey) == RegionHashManager.HashState.PARTIAL : "Nether hash must be preserved";
        System.out.println("  ✓ testDimensionClearing");
    }
}
