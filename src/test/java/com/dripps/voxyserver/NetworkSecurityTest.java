package com.dripps.voxyserver;

import com.dripps.voxyserver.network.LODBatchResultPayload;
import com.dripps.voxyserver.network.LODBulkPayload;
import com.dripps.voxyserver.network.LODManifestPayload;
import com.dripps.voxyserver.network.LODPreferencesPayload;
import com.dripps.voxyserver.network.LODRegionSyncPayload;
import com.dripps.voxyserver.network.PreSerializedLodPayload;
import com.dripps.voxyserver.server.PlayerLodTracker;
import net.minecraft.resources.Identifier;

/**
 * Regression and security test suite for network payloads and input validation on branch 26.3.
 */
public class NetworkSecurityTest {

    public static void runAllTests() {
        System.out.println("--- Running NetworkSecurityTest ---");
        testLODManifestPayloadValidation();
        testLODRegionSyncPayloadValidation();
        testPreferencesPayloadValidation();
        testPreSerializedPayloadBounds();
        testLODBatchResultValidation();
        testLODBulkPayloadValidation();
        testPlayerLodTrackerManifest();
        System.out.println("[PASS] NetworkSecurityTest completed successfully.\n");
    }

    private static void testLODManifestPayloadValidation() {
        Identifier dim = Identifier.parse("minecraft:overworld");
        long[] keys = new long[]{100L, 200L, 300L};
        long[] hashes = new long[]{0xABCDL, 0x1234L, 0x5678L};

        // Normal valid creation
        LODManifestPayload payload = new LODManifestPayload(dim, 1L, 10L, keys, hashes, LODManifestPayload.PART);
        assert payload.keys().length == 3 : "Expected 3 keys";
        assert payload.hashes().length == 3 : "Expected 3 hashes";
        assert payload.status() == LODManifestPayload.PART : "Expected PART status";

        // Mismatched keys vs hashes length
        boolean caughtMismatch = false;
        try {
            new LODManifestPayload(dim, 1L, 10L, new long[2], new long[3], LODManifestPayload.PART);
        } catch (IllegalArgumentException e) {
            caughtMismatch = true;
        }
        assert caughtMismatch : "Expected exception on mismatched key and hash lengths";

        // Null dimension
        boolean caughtNullDim = false;
        try {
            new LODManifestPayload(null, 1L, 10L, keys, hashes, LODManifestPayload.PART);
        } catch (IllegalArgumentException e) {
            caughtNullDim = true;
        }
        assert caughtNullDim : "Expected exception on null dimension";

        // Excessive count
        boolean caughtExcessive = false;
        try {
            long[] bigKeys = new long[LODManifestPayload.MAX_ENTRIES + 1];
            long[] bigHashes = new long[LODManifestPayload.MAX_ENTRIES + 1];
            new LODManifestPayload(dim, 1L, 10L, bigKeys, bigHashes, LODManifestPayload.PART);
        } catch (IllegalArgumentException e) {
            caughtExcessive = true;
        }
        assert caughtExcessive : "Expected exception when exceeding MAX_ENTRIES";

        // Invalid status
        boolean caughtBadStatus = false;
        try {
            new LODManifestPayload(dim, 1L, 10L, keys, hashes, 99);
        } catch (IllegalArgumentException e) {
            caughtBadStatus = true;
        }
        assert caughtBadStatus : "Expected exception on invalid status code";

        System.out.println("  ✓ testLODManifestPayloadValidation");
    }

    private static void testLODRegionSyncPayloadValidation() {
        long[] keys = new long[]{100L, 200L};
        long[] hashes = new long[]{0xABCDL, 0x1234L};

        LODRegionSyncPayload valid = new LODRegionSyncPayload(keys, hashes);
        assert valid.regionKeys().length == 2;
        assert valid.regionHashes().length == 2;

        boolean caughtMismatch = false;
        try {
            new LODRegionSyncPayload(new long[2], new long[1]);
        } catch (IllegalArgumentException e) {
            caughtMismatch = true;
        }
        assert caughtMismatch : "Expected exception on mismatched array lengths";

        boolean caughtNull = false;
        try {
            new LODRegionSyncPayload(null, hashes);
        } catch (IllegalArgumentException e) {
            caughtNull = true;
        }
        assert caughtNull : "Expected exception on null array";

        boolean caughtExcessive = false;
        try {
            long[] bigKeys = new long[LODRegionSyncPayload.MAX_REGIONS + 1];
            long[] bigHashes = new long[LODRegionSyncPayload.MAX_REGIONS + 1];
            new LODRegionSyncPayload(bigKeys, bigHashes);
        } catch (IllegalArgumentException e) {
            caughtExcessive = true;
        }
        assert caughtExcessive : "Expected exception on exceeding MAX_REGIONS";

        System.out.println("  ✓ testLODRegionSyncPayloadValidation");
    }

    private static void testPreferencesPayloadValidation() {
        // Normal valid preferences
        LODPreferencesPayload normal = new LODPreferencesPayload(true, 256, 100);
        assert normal.lodStreamRadius() == 256;
        assert normal.maxSectionsPerTick() == 100;

        // Negative radius rejected
        boolean caughtNegRadius = false;
        try {
            new LODPreferencesPayload(true, -10, 100);
        } catch (IllegalArgumentException e) {
            caughtNegRadius = true;
        }
        assert caughtNegRadius : "Negative radius should be rejected";

        // Excessive radius rejected
        boolean caughtHugeRadius = false;
        try {
            new LODPreferencesPayload(true, 5000, 100);
        } catch (IllegalArgumentException e) {
            caughtHugeRadius = true;
        }
        assert caughtHugeRadius : "Huge radius should be rejected";

        // Negative rate rejected
        boolean caughtNegRate = false;
        try {
            new LODPreferencesPayload(true, 256, -5);
        } catch (IllegalArgumentException e) {
            caughtNegRate = true;
        }
        assert caughtNegRate : "Negative rate should be rejected";

        System.out.println("  ✓ testPreferencesPayloadValidation");
    }

    private static void testPreSerializedPayloadBounds() {
        boolean caughtNull = false;
        try {
            new PreSerializedLodPayload(1L, 1L, 1, null);
        } catch (IllegalArgumentException e) {
            caughtNull = true;
        }
        assert caughtNull : "Expected exception on null data";

        boolean caughtHuge = false;
        try {
            byte[] huge = new byte[PreSerializedLodPayload.MAX_ENCODED_BYTES + 1];
            new PreSerializedLodPayload(1L, 1L, 1, huge);
        } catch (IllegalArgumentException e) {
            caughtHuge = true;
        }
        assert caughtHuge : "Expected exception when exceeding MAX_ENCODED_BYTES";

        boolean caughtBadCount = false;
        try {
            new PreSerializedLodPayload(1L, 1L, 0, new byte[10]);
        } catch (IllegalArgumentException e) {
            caughtBadCount = true;
        }
        assert caughtBadCount : "Expected exception on zero section count";

        System.out.println("  ✓ testPreSerializedPayloadBounds");
    }

    private static void testLODBatchResultValidation() {
        LODBatchResultPayload valid = new LODBatchResultPayload(1L, 5L, LODBatchResultPayload.SUCCESS);
        assert valid.streamEpoch() == 1L;
        assert valid.batchId() == 5L;
        assert valid.status() == LODBatchResultPayload.SUCCESS;

        boolean caughtNegEpoch = false;
        try {
            new LODBatchResultPayload(-1L, 5L, LODBatchResultPayload.SUCCESS);
        } catch (IllegalArgumentException e) {
            caughtNegEpoch = true;
        }
        assert caughtNegEpoch : "Negative epoch should be rejected";

        boolean caughtBadStatus = false;
        try {
            new LODBatchResultPayload(1L, 5L, 10);
        } catch (IllegalArgumentException e) {
            caughtBadStatus = true;
        }
        assert caughtBadStatus : "Invalid status should be rejected";

        System.out.println("  ✓ testLODBatchResultValidation");
    }

    private static void testLODBulkPayloadValidation() {
        Identifier dim = Identifier.parse("minecraft:overworld");

        boolean caughtNullSections = false;
        try {
            new LODBulkPayload(dim, null);
        } catch (IllegalArgumentException e) {
            caughtNullSections = true;
        }
        assert caughtNullSections : "Null sections in LODBulkPayload must be rejected";

        boolean caughtEmptySections = false;
        try {
            new LODBulkPayload(dim, java.util.Collections.emptyList());
        } catch (IllegalArgumentException e) {
            caughtEmptySections = true;
        }
        assert caughtEmptySections : "Empty sections in LODBulkPayload must be rejected";

        System.out.println("  ✓ testLODBulkPayloadValidation");
    }

    private static void testPlayerLodTrackerManifest() {
        PlayerLodTracker tracker = new PlayerLodTracker();
        Identifier dim = Identifier.parse("minecraft:overworld");
        PlayerLodTracker.StreamSession session = tracker.beginSession(dim, true, 100L);
        assert session.manifestRequired() : "Manifest should be required";

        long[] keys = new long[]{10L, 20L};
        long[] hashes = new long[]{111L, 222L};
        boolean applied = tracker.applyManifestBatch(dim, session.streamEpoch(), session.manifestRequestId(), 0, keys, hashes);
        assert applied : "Manifest batch should be applied";

        assert tracker.hasSent(10L, 111L) : "Tracker should recognize acknowledged section 10L";
        assert tracker.hasSent(20L, 222L) : "Tracker should recognize acknowledged section 20L";
        assert !tracker.hasSent(30L, 333L) : "Tracker should not recognize un-manifested section";

        System.out.println("  ✓ testPlayerLodTrackerManifest");
    }
}
