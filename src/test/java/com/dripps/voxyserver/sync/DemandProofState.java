package com.dripps.voxyserver.sync;

import com.dripps.voxyserver.network.LODDemandReplyPayload;

/**
 * One fixed request/range. Owned under the player's monitor; no I/O or mutable client claims escape
 * it.
 */
public class DemandProofState {
    public final long request, wanted;
    public long coverage, revision, needed;
    public long[] hashes;
    public boolean detailed, invalidated;

    public DemandProofState(long request, long wanted) {
        if (request <= 0 || wanted == 0)
            throw new IllegalArgumentException("invalid proof identity");
        this.request = request;
        this.wanted = wanted;
    }

    /** No published snapshot exists while hashes is null. Unrequested siblings are independent. */
    public boolean affectedBy(long changedSlots) {
        return hashes != null && (wanted & changedSlots) != 0;
    }

    public int summary(
            long present,
            long version,
            long[] leaves,
            long clientCoverage,
            long root,
            boolean reuse) {
        if (invalidated) return LODDemandReplyPayload.INVALIDATED;
        if (detailed) return LODDemandReplyPayload.ACCEPTED;
        if ((present & ~wanted) != 0 || leaves.length != 64)
            throw new IllegalArgumentException("invalid proof range");
        coverage = present;
        revision = version;
        hashes = leaves.clone();
        return reuse
                        && clientCoverage == present
                        && root != 0
                        && root == DemandGroup.digest(present, hashes)
                ? LODDemandReplyPayload.MATCH
                : LODDemandReplyPayload.DIFFERENT;
    }

    public int detail(
            long present,
            long currentRevision,
            long claimedRevision,
            long clientCoverage,
            long[] client,
            boolean reuse) {
        if (invalidated
                || hashes == null
                || revision != currentRevision
                || claimedRevision != currentRevision
                || clientCoverage != present
                || coverage != present) return LODDemandReplyPayload.INVALIDATED;
        if (!detailed) {
            needed = DemandComparison.needed(present, hashes, client, reuse);
            detailed = true;
        }
        return LODDemandReplyPayload.ACCEPTED;
    }

    public boolean confirm(long request, long slots, boolean success) {
        if (request != this.request || invalidated || !detailed) return false;
        if (success) needed &= ~slots;
        return true;
    }
}
