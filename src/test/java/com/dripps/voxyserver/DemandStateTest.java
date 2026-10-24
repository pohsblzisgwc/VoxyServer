package com.dripps.voxyserver;

import com.dripps.voxyserver.network.LODDemandReplyPayload;
import com.dripps.voxyserver.server.PlayerLodTracker;
import com.dripps.voxyserver.sync.*;

import net.minecraft.resources.Identifier;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class DemandStateTest {
    public static void runAllTests() throws Exception {
        long[] h1 = new long[64], h2 = new long[64];
        Arrays.fill(h1, 11);
        Arrays.fill(h2, 11);
        h2[1] = 22;
        var old = new DemandProofState(1, 7);
        var fresh = new DemandProofState(2, 7);
        var outside = new DemandProofState(3, 8);
        require(
                old.summary(7, 10, h1, 7, DemandGroup.digest(7, h1), true)
                        == LODDemandReplyPayload.MATCH,
                "H1 not reused");
        require(
                fresh.summary(3, 20, h2, 7, DemandGroup.digest(7, h1), true)
                        == LODDemandReplyPayload.DIFFERENT,
                "changed/deleted leaves hidden");
        require(
                fresh.detail(3, 20, 20, 3, h1, true) == LODDemandReplyPayload.ACCEPTED
                        && fresh.needed == 2,
                "H1 to H2 should send only changed server-present leaf");
        require(fresh.confirm(2, 2, true) && fresh.needed == 0, "delivery confirmation failed");
        require(
                fresh.detail(3, 20, 20, 3, h1, true) == LODDemandReplyPayload.ACCEPTED
                        && fresh.needed == 0,
                "duplicate detail resurrected confirmed work");
        require(
                fresh.summary(3, 20, h2, 7, 0, true) == LODDemandReplyPayload.ACCEPTED,
                "late SUMMARY rolled proof backwards");
        require(!fresh.confirm(1, 7, true), "old batch ACK modified new proof");
        require(
                outside.summary(8, 33, h1, 8, DemandGroup.digest(8, h1), true)
                        == LODDemandReplyPayload.MATCH,
                "outside proof lost");
        var stale = new DemandProofState(4, 7);
        stale.summary(7, 40, h1, 0, 0, true);
        require(
                stale.detail(7, 41, 40, 7, h1, true) == LODDemandReplyPayload.INVALIDATED,
                "changed server revision accepted");
        stale.invalidated = true;
        require(
                stale.summary(7, 41, h2, 7, 123, true) == LODDemandReplyPayload.INVALIDATED,
                "cancelled proof revived");
        var missing = new DemandProofState(5, 1);
        missing.summary(1, 50, h1, 1, 123, true);
        require(
                missing.detail(1, 50, 50, 1, new long[64], true) == LODDemandReplyPayload.ACCEPTED
                        && missing.needed == 1,
                "deleted client section skipped");
        var absent = new DemandProofState(6, 7);
        absent.summary(0, 60, new long[64], 7, 123, true);
        require(
                absent.detail(0, 60, 60, 0, h1, true) == LODDemandReplyPayload.ACCEPTED
                        && absent.needed == 0,
                "server absence fabricated a replacement");
        var tracker = new PlayerLodTracker();
        var dim = Identifier.parse("minecraft:overworld");
        var session = tracker.beginSession(dim, false, 0);
        var batch =
                tracker.reserveBatch(
                        dim, session.streamEpoch(), new long[] {0}, new long[] {11}, 100, 0);
        tracker.supersedeSections(new long[] {0});
        require(
                tracker.completeDemandBatch(session.streamEpoch(), batch.batchId(), true, 1)
                        && !tracker.hasKnownSection(0),
                "superseded ACK retained stale content");
        for (int i = 0; i < 2000; i++) {
            var failed =
                    tracker.reserveBatch(
                            dim, session.streamEpoch(), new long[] {i}, new long[] {11}, 100, 0);
            require(failed != null, "failed batch credit leak");
            tracker.completeDemandBatch(session.streamEpoch(), failed.batchId(), false, 1);
        }
        require(
                tracker.syncStats().outstandingBytes() == 0 && !tracker.hasDeferredDirtySections(),
                "demand failures leaked legacy scan work/resources");
        var timed =
                tracker.reserveBatch(
                        dim, session.streamEpoch(), new long[] {0}, new long[] {11}, 100, 0);
        require(
                tracker.expiredDemandBatches(600, 600).equals(List.of(timed.batchId())),
                "ACK timeout not recoverable");
        tracker.completeDemandBatch(session.streamEpoch(), timed.batchId(), false, 600);
        require(tracker.remainingSectionCredit() == 512, "timeout leaked section credit");
        leaseRace();
        fairness();
        clientRecovery();
        var local = new ClientDemandState();
        local.wanted = 3;
        local.coverage = 3;
        local.beginLocalProof();
        local.observeLocalPresence(1, true);
        local.observeLocalPresence(0, false);
        long[] cached = new long[64];
        cached[1] = 42;
        var same = new DemandProofState(9001, 3);
        require(
                local.localCoverage == 2
                        && same.summary(
                                        2,
                                        1,
                                        cached,
                                        local.localCoverage,
                                        DemandGroup.digest(local.localCoverage, cached),
                                        true)
                                == LODDemandReplyPayload.MATCH,
                "first local cache proof omitted actual presence and forced retransmission");
        local.beginLocalProof();
        local.observeLocalPresence(1, false);
        require(
                local.localCoverage == 0 && local.coverage == 3,
                "local deletion retained stale coverage or overwrote server scope");
        var missingLocal = new DemandProofState(9002, 3);
        require(
                missingLocal.summary(
                                2,
                                1,
                                cached,
                                local.localCoverage,
                                DemandGroup.digest(0, cached),
                                true)
                        == LODDemandReplyPayload.DIFFERENT,
                "deleted local content incorrectly matched");
        System.out.println(
                "PASS DemandStateTest: H1/H2, deletion/absence, replay, late ACK, credits, lease"
                        + " race and fair admission");
    }

    private static void clientRecovery() {
        var c = new ClientDemandState();
        c.wanted = 3;
        c.begin(7, 1);
        long group = 0, now = 1_000_000_000L;
        var dim = Identifier.parse("minecraft:overworld");
        require(c.takeAttempt(now), "initial proof delayed");
        require(
                !c.onReply(
                        new com.dripps.voxyserver.network.LODDemandReplyPayload(
                                dim, 6, 1, group, 1, 3, 3, 10, 0),
                        now),
                "old epoch reply accepted");
        c.onReply(
                new com.dripps.voxyserver.network.LODDemandReplyPayload(
                        dim, 7, 1, group, 1, 3, 3, 10, 0),
                now);
        require(c.phase == 1, "detail not requested");
        require(
                !c.onReply(
                        new com.dripps.voxyserver.network.LODDemandReplyPayload(
                                dim, 7, 1, group, 0, 3, 3, 10, 0),
                        now),
                "late MATCH skipped detail");
        c.nodeReceived(
                0,
                now); // Data can reach the ingest worker before the reply worker handles ACCEPTED.
        c.onReply(
                new com.dripps.voxyserver.network.LODDemandReplyPayload(
                        dim, 7, 1, group, 3, 3, 3, 10, 3),
                now);
        require(
                c.needed == 2 && c.phase == ClientDemandState.WAIT_DATA,
                "early delivery was forgotten");
        c.nodeReceived(1, now);
        require(
                c.request == 0 && c.verifiedRequest == 1 && c.due >= now + 150_000_000_000L,
                "completed proof not retained for low-frequency audit");
        c.onReply(
                new com.dripps.voxyserver.network.LODDemandReplyPayload(
                        dim, 7, 1, group, 5, 3, 3, 10, 0),
                now + 1);
        require(c.due == now + 1 + 2_000_000_000L, "idle completed proof ignored server dirtiness");
        c.begin(7, 2);
        require(
                !c.onReply(
                        new com.dripps.voxyserver.network.LODDemandReplyPayload(
                                dim, 7, 1, group, 1, 3, 3, 10, 0),
                        now),
                "old request revived");
        for (int i = 0; i < 16; i++)
            require(c.takeAttempt(now + i * 2_000_000_000L), "finite retry allowance too small");
        require(
                !c.takeAttempt(now + 32_000_000_000L) && c.request == 0,
                "PENDING proof retried without a bound");
        c.begin(7, 3);
        c.onReply(
                new com.dripps.voxyserver.network.LODDemandReplyPayload(
                        dim, 7, 3, group, 1, 3, 3, 11, 0),
                now);
        c.onReply(
                new com.dripps.voxyserver.network.LODDemandReplyPayload(
                        dim, 7, 3, group, 3, 3, 3, 11, 1),
                now);
        require(c.expireData(now + 31_000_000_000L) && c.request == 0, "lost data never recovered");
        c.begin(7, 4);
        require(c.takeAttempt(now + 40_000_000_000L), "proof did not resume after timeout");
    }

    private static void leaseRace() throws Exception {
        var lease = new DemandLease();
        lease.begin(9, 1, 1);
        var writes = new AtomicInteger();
        require(lease.apply(9, 1, 0, writes::incrementAndGet), "current lease rejected");
        lease.begin(9, 2, 2);
        require(!lease.apply(9, 1, 0, writes::incrementAndGet), "old request installed");
        require(!lease.apply(8, 2, 1, writes::incrementAndGet), "old epoch installed");
        require(!lease.apply(9, 2, 0, writes::incrementAndGet), "outside scope installed");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var replaced = new AtomicBoolean();
        var writer =
                new Thread(
                        () ->
                                lease.apply(
                                        9,
                                        2,
                                        1,
                                        () -> {
                                            entered.countDown();
                                            await(release);
                                            writes.incrementAndGet();
                                        }));
        writer.start();
        require(entered.await(5, TimeUnit.SECONDS), "installer stalled");
        var replace =
                new Thread(
                        () -> {
                            lease.invalidate();
                            replaced.set(true);
                        });
        replace.start();
        require(!replaced.get(), "lease replaced inside active installation");
        release.countDown();
        writer.join(5000);
        replace.join(5000);
        require(
                !writer.isAlive() && !replace.isAlive() && replaced.get(),
                "lease replacement deadlocked");
        require(
                !lease.apply(9, 2, 1, writes::incrementAndGet) && writes.get() == 2,
                "cancelled install ran");
    }

    private static void fairness() {
        var queue = new FairWorkQueue<Integer, Integer>(256, 4);
        for (int n = 0; n < 4; n++) require(queue.offer(0, n), "first admission failed");
        require(!queue.offer(0, 4), "one player monopolized mailbox");
        for (int player = 1; player < 64; player++)
            for (int n = 0; n < 4; n++)
                require(queue.offer(player, player * 10 + n), "bounded mailbox too small");
        require(queue.size() == 256 && !queue.offer(999, 999), "global cap exceeded");
        for (int player = 0; player < 64; player++)
            require(queue.poll() == player * 10, "mailbox not round robin");
        queue.remove(0);
        require(queue.size() == 189, "cancelled mailbox leaked capacity");
        while (queue.poll() != null) {}
        require(queue.size() == 0, "mailbox count became inconsistent");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("test timed out");
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
