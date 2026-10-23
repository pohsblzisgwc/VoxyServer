package com.dripps.voxyserver;

import com.dripps.voxyserver.network.PreSerializedLodPayload;
import com.dripps.voxyserver.server.throttle.BandwidthThrottler;
import com.dripps.voxyserver.server.throttle.PlayerOutboundQueue;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Unit and integration tests for BandwidthThrottler token bucket,
 * multi-player isolation, and bounded dual-priority outbound queue.
 */
public class BandwidthThrottlerTest {

    public static void runAllTests() {
        System.out.println("--- Running BandwidthThrottlerTest ---");
        testStrictDeduction();
        testBurstCapacityFloor();
        testTokenRefillMonotonic();
        testUnlimitedBandwidthMode();
        testMultiPlayerIsolation();
        testPlayerOutboundQueuePriorityAndEviction();
        testQueueDimensionClear();
        testRuntimeBandwidthUpdate();
        System.out.println("[PASS] BandwidthThrottlerTest completed successfully.\n");
    }

    private static void testStrictDeduction() {
        // Create bucket with 100 KB/s
        BandwidthThrottler.TokenBucket bucket = new BandwidthThrottler.TokenBucket(100_000);
        long initialTokens = bucket.getAvailableTokens();

        // Attempting to acquire more tokens than available must return false
        long excessiveBytes = initialTokens + 5000;
        boolean acquired = bucket.tryAcquire(excessiveBytes);
        assert !acquired : "tryAcquire must return false when requested bytes exceed available tokens";
        assert bucket.getAvailableTokens() == initialTokens : "Tokens must NOT be deducted on failed acquisition";
        assert bucket.getAvailableTokens() >= 0 : "Tokens must never become negative";

        // Acquiring exact or fewer tokens must succeed and deduct precisely
        boolean success = bucket.tryAcquire(50_000);
        assert success : "tryAcquire must return true when tokens are sufficient";
        assert bucket.getAvailableTokens() == initialTokens - 50_000 : "Tokens must be deducted by exact byte amount";
        System.out.println("  ✓ testStrictDeduction");
    }

    private static void testBurstCapacityFloor() {
        // Low bandwidth configuration: 20 KB/s
        long lowBps = 20_000;
        BandwidthThrottler.TokenBucket bucket = new BandwidthThrottler.TokenBucket(lowBps);

        // Max burst must be at least MIN_BURST_BYTES (512 KB) so a 200 KB LOD packet is never permanently blocked
        assert bucket.getMaxBurst() >= BandwidthThrottler.MIN_BURST_BYTES :
                "Max burst (" + bucket.getMaxBurst() + ") must be >= MIN_BURST_BYTES (" + BandwidthThrottler.MIN_BURST_BYTES + ")";
        System.out.println("  ✓ testBurstCapacityFloor");
    }

    private static void testTokenRefillMonotonic() {
        long bps = 200_000;
        BandwidthThrottler.TokenBucket bucket = new BandwidthThrottler.TokenBucket(bps);

        // Drain tokens
        bucket.tryAcquire(bucket.getAvailableTokens());
        assert bucket.getAvailableTokens() == 0 : "Bucket should be empty after full acquisition";

        // Simulate tick refill with 20 ticks (1 second)
        bucket.refill(0);
        bucket.refill(20);
        long refilled = bucket.getAvailableTokens();
        assert refilled > 0 : "Tokens should have refilled after 20 ticks";
        assert refilled <= bucket.getMaxBurst() : "Refilled tokens must not exceed maxBurst";
        System.out.println("  ✓ testTokenRefillMonotonic");
    }

    private static void testUnlimitedBandwidthMode() {
        // Zero or negative bandwidth indicates unlimited
        BandwidthThrottler.TokenBucket bucketZero = new BandwidthThrottler.TokenBucket(0);
        assert bucketZero.isUnlimited() : "0 bps should be unlimited";
        assert bucketZero.tryAcquire(100_000_000L) : "Unlimited bucket must allow large packet immediately";

        BandwidthThrottler.TokenBucket bucketNeg = new BandwidthThrottler.TokenBucket(-500);
        assert bucketNeg.isUnlimited() : "Negative bps should be unlimited";
        assert bucketNeg.tryAcquire(50_000_000L) : "Negative bps bucket must allow send immediately";
        System.out.println("  ✓ testUnlimitedBandwidthMode");
    }

    private static void testMultiPlayerIsolation() {
        BandwidthThrottler throttler = new BandwidthThrottler();
        UUID player1 = UUID.randomUUID();
        UUID player2 = UUID.randomUUID();

        throttler.registerPlayer(player1, 100_000);
        throttler.registerPlayer(player2, 100_000);

        long p2InitialTokens = throttler.getBucket(player2).getAvailableTokens();

        // Player 1 sends heavy traffic
        throttler.canSend(player1, 50_000);

        // Player 2's tokens must remain completely unaffected
        assert throttler.getBucket(player2).getAvailableTokens() == p2InitialTokens :
                "Player 1 traffic must not deduct or affect Player 2 token budget";

        throttler.unregisterPlayer(player1);
        throttler.unregisterPlayer(player2);
        assert throttler.getBucket(player1) == null : "Unregistered player bucket must be cleaned up";
        System.out.println("  ✓ testMultiPlayerIsolation");
    }

    private static void testPlayerOutboundQueuePriorityAndEviction() {
        PlayerOutboundQueue queue = new PlayerOutboundQueue();
        Identifier dim = Identifier.parse("minecraft:overworld");

        // Enqueue background scan packets (normal priority)
        byte[] dummyData = new byte[1024]; // 1 KB
        PreSerializedLodPayload payload = new PreSerializedLodPayload(1L, 1L, 1, dummyData);

        for (int i = 0; i < PlayerOutboundQueue.MAX_QUEUED_PACKETS; i++) {
            boolean ok = queue.enqueue(new PlayerOutboundQueue.QueuedPacket(
                    payload, dim, 1L, i, false, System.nanoTime()
            ), null);
            assert ok : "Normal packet " + i + " should be queued";
        }

        // When queue is full, another normal packet must be rejected
        boolean rejected = queue.enqueue(new PlayerOutboundQueue.QueuedPacket(
                payload, dim, 1L, 999L, false, System.nanoTime()
        ), null);
        assert !rejected : "Queue must reject normal packet when at maximum capacity";
        assert queue.getDroppedCount() == 1 : "Dropped count should increment on normal packet rejection";

        // However, a HIGH PRIORITY dirty update packet MUST be accepted by evicting oldest normal packet
        List<PlayerOutboundQueue.QueuedPacket> evicted = new ArrayList<>();
        boolean highAccepted = queue.enqueue(new PlayerOutboundQueue.QueuedPacket(
                payload, dim, 1L, 1000L, true, System.nanoTime()
        ), evicted);
        assert highAccepted : "High priority dirty section packet must be enqueued via normal packet eviction";
        assert !evicted.isEmpty() : "Evicted packet list must not be empty";
        assert queue.getDroppedCount() == 2 : "Evicted normal packet should count towards dropped count";

        // Next polled packet MUST be the high priority packet
        PlayerOutboundQueue.QueuedPacket polled = queue.pollNext();
        assert polled != null && polled.highPriority() : "Polled packet must be the high priority packet";
        System.out.println("  ✓ testPlayerOutboundQueuePriorityAndEviction");
    }

    private static void testQueueDimensionClear() {
        PlayerOutboundQueue queue = new PlayerOutboundQueue();
        Identifier overworld = Identifier.parse("minecraft:overworld");
        Identifier nether = Identifier.parse("minecraft:the_nether");

        byte[] dummyData = new byte[512];
        PreSerializedLodPayload payload = new PreSerializedLodPayload(1L, 1L, 1, dummyData);

        queue.enqueue(new PlayerOutboundQueue.QueuedPacket(payload, overworld, 1L, 1L, false, System.nanoTime()), null);
        queue.enqueue(new PlayerOutboundQueue.QueuedPacket(payload, overworld, 1L, 2L, false, System.nanoTime()), null);
        queue.enqueue(new PlayerOutboundQueue.QueuedPacket(payload, nether, 1L, 3L, false, System.nanoTime()), null);

        assert queue.getQueuedPacketCount() == 3 : "Expected 3 queued packets";

        // Clearing overworld dimension should leave only nether packet
        List<PlayerOutboundQueue.QueuedPacket> cleared = queue.clearDimension(overworld);
        assert cleared.size() == 2 : "Expected 2 cleared packets";
        assert queue.getQueuedPacketCount() == 1 : "Expected 1 packet remaining after dimension clear";
        assert queue.peekNext().dimension().equals(nether) : "Remaining packet must be nether";
        System.out.println("  ✓ testQueueDimensionClear");
    }

    private static void testRuntimeBandwidthUpdate() {
        BandwidthThrottler throttler = new BandwidthThrottler();
        UUID player1 = UUID.randomUUID();
        UUID player2 = UUID.randomUUID();

        // 1. Initial registration at 100 KB/s
        throttler.registerPlayer(player1, 100_000);
        throttler.registerPlayer(player2, 100_000);

        BandwidthThrottler.TokenBucket b1 = throttler.getBucket(player1);
        BandwidthThrottler.TokenBucket b2 = throttler.getBucket(player2);

        assert b1.getBytesPerSecond() == 100_000 : "Initial bps must be 100_000";
        assert !b1.isUnlimited() : "Bucket must not be unlimited";

        // 2. Runtime update to 500 KB/s
        throttler.updateAllBandwidth(500_000);
        assert b1.getBytesPerSecond() == 500_000 : "b1 bps must be updated to 500_000";
        assert b2.getBytesPerSecond() == 500_000 : "b2 bps must be updated to 500_000";
        assert b1.getMaxBurst() == 1_000_000 : "maxBurst must be 1_000_000";

        // 3. Runtime update to unlimited (0)
        throttler.updateAllBandwidth(0);
        assert b1.isUnlimited() : "b1 must be unlimited";
        assert b2.isUnlimited() : "b2 must be unlimited";
        assert b1.getAvailableTokens() == Long.MAX_VALUE : "Tokens must be MAX_VALUE when unlimited";

        // 4. Runtime update back to throttled (200 KB/s)
        throttler.updateAllBandwidth(200_000);
        assert !b1.isUnlimited() : "b1 must be throttled again";
        assert b1.getBytesPerSecond() == 200_000 : "b1 bps must be 200_000";
        assert b1.getAvailableTokens() <= b1.getMaxBurst() : "Available tokens must be clamped to maxBurst";

        System.out.println("  ✓ testRuntimeBandwidthUpdate");
    }
}
