package com.dripps.voxyserver.sync;

import com.dripps.voxyserver.network.*;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Client proof/retry state, separated from renderer and storage access for deterministic recovery
 * tests.
 */
public class ClientDemandState {
    public static final int WAIT_DATA = 3;
    public final DemandLease lease = new DemandLease();
    public long localCoverage;

    public void beginLocalProof() {
        localCoverage = 0;
    }

    public void observeLocalPresence(int slot, boolean present) {
        if (slot < 0 || slot >= 64) throw new IllegalArgumentException("invalid local proof slot");
        long bit = 1L << slot;
        localCoverage = present ? localCoverage | bit : localCoverage & ~bit;
    }

    public long wanted,
            coverage,
            revision,
            request,
            verifiedRequest,
            due,
            lastSend,
            needed,
            received,
            epoch;
    public int phase, retries;
    public boolean dirty;

    public void begin(long epoch, long request) {
        this.epoch = epoch;
        this.request = request;
        phase = LODDemandPayload.SUMMARY;
        retries = 0;
        lastSend = 0;
        needed = 0;
        received = 0;
        lease.begin(epoch, request, wanted);
    }

    public void restart(long due) {
        lease.invalidate();
        request = 0;
        this.due = due;
    }

    public void finish(long now) {
        lease.invalidate();
        verifiedRequest = request;
        request = 0;
        due =
                now
                        + (dirty
                                ? 500_000_000L
                                : ThreadLocalRandom.current().nextLong(150, 211) * 1_000_000_000L);
        dirty = false;
    }

    public boolean onReply(LODDemandReplyPayload p, long now) {
        if (p.epoch() != epoch
                || p.wanted() != wanted
                || (p.requestId() != request
                        && !(request == 0
                                && p.requestId() == verifiedRequest
                                && p.status() == LODDemandReplyPayload.INVALIDATED))) return false;
        int status = p.status();
        if (status == LODDemandReplyPayload.PENDING) return false;
        if (status == LODDemandReplyPayload.INVALIDATED
                || status == LODDemandReplyPayload.REJECTED) {
            restart(now + 2_000_000_000L);
            return false;
        }
        if (status == LODDemandReplyPayload.DIFFERENT && phase != LODDemandPayload.SUMMARY)
            return false;
        if (status == LODDemandReplyPayload.MATCH && phase != LODDemandPayload.SUMMARY)
            return false;
        if (status == LODDemandReplyPayload.ACCEPTED && phase != LODDemandPayload.DETAIL)
            return false;
        coverage = p.coverage();
        revision = p.revision();
        retries = 0;
        if (status == LODDemandReplyPayload.DIFFERENT) {
            phase = LODDemandPayload.DETAIL;
            lastSend = 0;
        } else if (status == LODDemandReplyPayload.ACCEPTED) {
            needed = p.needed() & ~received;
            phase = WAIT_DATA;
            lastSend = now;
            if (needed == 0) finish(now);
        } else if (status == LODDemandReplyPayload.MATCH) finish(now);
        return true;
    }

    public void nodeReceived(int slot, long now) {
        long bit = 1L << slot;
        received |= bit;
        needed &= ~bit;
        if (phase == WAIT_DATA && needed == 0) finish(now);
    }

    public boolean expireData(long now) {
        if (request != 0 && phase == WAIT_DATA && now - lastSend > 30_000_000_000L) {
            restart(now + 5_000_000_000L);
            return true;
        }
        return false;
    }

    public boolean takeAttempt(long now) {
        if (request == 0 || phase == WAIT_DATA) return false;
        long delay = phase == LODDemandPayload.SUMMARY ? 2_000_000_000L : 5_000_000_000L;
        if (lastSend != 0 && now - lastSend < delay) return false;
        if (retries++ >= 16) {
            restart(now + 10_000_000_000L);
            return false;
        }
        lastSend = now;
        return true;
    }
}
