package com.dripps.voxyserver.sync;

/** Serializes client proof replacement with installation of a node from that proof. */
public final class DemandLease {
    private long epoch, request, wanted;

    public synchronized void begin(long epoch, long request, long wanted) {
        if (epoch <= 0 || request <= 0 || wanted == 0)
            throw new IllegalArgumentException("invalid lease");
        this.epoch = epoch;
        this.request = request;
        this.wanted = wanted;
    }

    public synchronized void invalidate() {
        wanted = 0;
    }

    public synchronized boolean apply(long epoch, long request, int slot, Runnable install) {
        if (this.epoch != epoch
                || this.request != request
                || slot < 0
                || slot >= 64
                || (wanted & (1L << slot)) == 0) return false;
        install.run();
        return true;
    }
}
