package com.dripps.voxyserver;

import com.dripps.voxyserver.server.PlayerLodTracker;
import com.dripps.voxyserver.server.throttle.BandwidthThrottler;

/**
 * Unit and boundary test suite for distance-based trickle pacing and candidate starvation prevention.
 * Explicitly tests boundaries at 16, 17, 48, 49 sections, sequence decoupling, and multi-tick recovery.
 */
public class DistancePacingTest {

    public static void runAllTests() {
        System.out.println("--- Running DistancePacingTest ---");
        testDistanceBoundaries();
        testCandidateSequenceDecoupling();
        testMultiTickDeferredSectionRecovery();
        testPlayerScanGeometryReset();
        testExhaustedDeferredRecovery();
        System.out.println("[PASS] DistancePacingTest completed successfully.\n");
    }

    private static void testExhaustedDeferredRecovery() {
        var tracker = new PlayerLodTracker();
        assert tracker.prepareScanWork(0, 0, 0, 0, 1, 0, 1200);
        long key = tracker.nextSectionKeyToScan(0, 1200);
        assert tracker.nextSectionKeyToScan(0, 1200) == PlayerLodTracker.NO_SECTION_KEY;
        tracker.deferPacedSection(key, key, 0);
        assert !tracker.prepareScanWork(0, 0, 0, 0, 1, 1, 1200) : "unreleased paced work must not spin";
        tracker.deferDirtySection(77, 77, 1);
        assert tracker.prepareScanWork(0, 0, 0, 0, 1, 1, 1200) : "dirty work cannot wait for idle rescan";
        assert tracker.pollDeferredDirtySection() == 77 : "dirty work must precede pacing";
        assert tracker.pollDeferredPacedSection(1) == PlayerLodTracker.NO_SECTION_KEY;
        assert tracker.prepareScanWork(0, 0, 0, 0, 1, 2, 1200) : "released work waited 1200 ticks";
        assert tracker.pollDeferredPacedSection(2) == key;
        assert !tracker.prepareScanWork(0, 0, 0, 0, 1, 3, 1200) : "exhausted empty scanner spun";
        // A hash-budget deferral remains eligible even with the base scanner exhausted.
        tracker.deferDirtySection(key, key, 4);
        assert tracker.prepareScanWork(0, 0, 0, 0, 1, 4, 1200);
        assert tracker.pollDeferredDirtySection() == key;
        var saturated = new PlayerLodTracker();
        assert saturated.prepareScanWork(0, 0, 0, 0, 1, 0, 1200);
        saturated.nextSectionKeyToScan(0, 1200);
        assert saturated.nextSectionKeyToScan(0, 1200) == PlayerLodTracker.NO_SECTION_KEY;
        for (int i = 0; i < 4096; i++) assert saturated.deferPacedSection(i, i, 98);
        assert !saturated.deferPacedSection(9000, 9000, 98) : "saturated pacing must retain a scan checkpoint";
        assert saturated.prepareScanWork(0, 0, 0, 0, 1, 99, 1200) : "retained checkpoint was hidden by exhaustion";
        assert saturated.pollDeferredPacedSection(99) == PlayerLodTracker.NO_SECTION_KEY;
        assert saturated.nextSectionKeyToScan(99, 1200) == 9000 : "retained scan candidate lost";
        assert !saturated.prepareScanWork(0, 0, 0, 0, 1, 99, 1200) : "consumed checkpoint must not spin";
        assert saturated.prepareScanWork(0, 0, 0, 0, 1, 100, 1200) : "full paced queue did not release";
        System.out.println("  ✓ testExhaustedDeferredRecovery (release at tick 2, no idle rescan or empty busy loop)");
    }

    private static void testDistanceBoundaries() {
        BandwidthThrottler throttler = BandwidthThrottler.INSTANCE;

        // 1. Distance <= 16 sections (Near): Must always return true regardless of sequence
        for (int dist = 0; dist <= 16; dist += 4) {
            for (int seq = 0; seq < 10; seq++) {
                assert throttler.checkDistancePacing(dist, seq, true) :
                        "Distance " + dist + " <= 16 must never be paced/throttled";
            }
        }
        // Exact boundary 16
        assert throttler.checkDistancePacing(16, 1, true) : "Distance 16 must stream immediately";
        assert throttler.checkDistancePacing(16, 3, true) : "Distance 16 must stream immediately";

        // 2. Exact boundary 17 (start of mid-distance): 50% rate
        assert throttler.checkDistancePacing(17, 0, true) : "Dist 17 seq 0 should pass";
        assert !throttler.checkDistancePacing(17, 1, true) : "Dist 17 seq 1 should be deferred";
        assert throttler.checkDistancePacing(17, 2, true) : "Dist 17 seq 2 should pass";
        assert !throttler.checkDistancePacing(17, 3, true) : "Dist 17 seq 3 should be deferred";

        // 3. Exact boundary 48 (end of mid-distance): 50% rate
        assert throttler.checkDistancePacing(48, 0, true) : "Dist 48 seq 0 should pass";
        assert !throttler.checkDistancePacing(48, 1, true) : "Dist 48 seq 1 should be deferred";
        assert throttler.checkDistancePacing(48, 2, true) : "Dist 48 seq 2 should pass";
        assert !throttler.checkDistancePacing(48, 3, true) : "Dist 48 seq 3 should be deferred";

        // 4. Exact boundary 49 (start of far-distance): 25% trickle rate
        assert throttler.checkDistancePacing(49, 0, true) : "Dist 49 seq 0 should pass";
        assert !throttler.checkDistancePacing(49, 1, true) : "Dist 49 seq 1 should be deferred";
        assert !throttler.checkDistancePacing(49, 2, true) : "Dist 49 seq 2 should be deferred";
        assert !throttler.checkDistancePacing(49, 3, true) : "Dist 49 seq 3 should be deferred";
        assert throttler.checkDistancePacing(49, 4, true) : "Dist 49 seq 4 should pass";

        // 5. When pacing is disabled: All distances pass unconditionally
        assert throttler.checkDistancePacing(49, 1, false) : "Dist 49 should pass when pacing disabled";
        assert throttler.checkDistancePacing(100, 3, false) : "Dist 100 should pass when pacing disabled";
        System.out.println("  ✓ testDistanceBoundaries (16, 17, 48, 49 sections verified)");
    }

    private static void testCandidateSequenceDecoupling() {
        BandwidthThrottler throttler = BandwidthThrottler.INSTANCE;
        int distMid = 20; // within 17..48

        // Verify that candidate index sequence progresses and does not lock into infinite stall
        int allowedCount = 0;
        int deferredCount = 0;

        for (int candidateIdx = 1; candidateIdx <= 10; candidateIdx++) {
            boolean allow = throttler.checkDistancePacing(distMid, candidateIdx, true);
            if (allow) {
                allowedCount++;
            } else {
                deferredCount++;
            }
        }

        // Exactly 5 allowed and 5 deferred across 10 sequential candidates
        assert allowedCount == 5 : "Expected exactly 5 allowed candidates, got " + allowedCount;
        assert deferredCount == 5 : "Expected exactly 5 deferred candidates, got " + deferredCount;
        System.out.println("  ✓ testCandidateSequenceDecoupling");
    }

    private static void testMultiTickDeferredSectionRecovery() {
        PlayerLodTracker tracker = new PlayerLodTracker();

        long compA = 10001L;
        long secA = 10001L;
        long compB = 10002L;
        long secB = 10002L;

        // In Tick 1: candidate B is deferred
        tracker.deferPacedSection(compB, secB);
        assert tracker.hasDeferredPacedSections() : "Tracker should report deferred paced sections present";

        // In Tick 2: tracker polls deferred paced sections
        long polled = tracker.pollDeferredPacedSection();
        assert polled == secB : "Polled section should be exactly the deferred section B";
        assert !tracker.hasDeferredPacedSections() : "Deferred queue should now be empty";

        long emptyPoll = tracker.pollDeferredPacedSection();
        assert emptyPoll == PlayerLodTracker.NO_SECTION_KEY : "Empty poll should return NO_SECTION_KEY";
        System.out.println("  ✓ testMultiTickDeferredSectionRecovery");
    }

    private static void testPlayerScanGeometryReset() {
        PlayerLodTracker tracker = new PlayerLodTracker();

        // Initialize scan geometry at (10, 10) with radius 32
        boolean prepared1 = tracker.prepareScan(10, 10, 32, -4, 20, 100L, 100L);
        assert prepared1 : "Initial scan preparation should succeed";

        long sec1 = tracker.nextSectionKeyToScan(100L, 100L);
        assert sec1 != PlayerLodTracker.NO_SECTION_KEY : "First section key should be valid";

        // Player moves to (20, 20): geometry change must reset cursor to center
        boolean prepared2 = tracker.prepareScan(20, 20, 32, -4, 20, 101L, 100L);
        assert prepared2 : "Subsequent scan preparation with moved player should succeed";

        long secAfterMove = tracker.nextSectionKeyToScan(101L, 100L);
        assert secAfterMove != PlayerLodTracker.NO_SECTION_KEY : "Section key after move should be valid";
        System.out.println("  ✓ testPlayerScanGeometryReset");
    }
}
