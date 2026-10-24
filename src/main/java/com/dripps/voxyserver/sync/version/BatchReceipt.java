package com.dripps.voxyserver.sync.version;

/** Timeout starts on actual network send. Completion is idempotent and bound to epoch/batch. */
public final class BatchReceipt {
    private final long epoch, batch;
    private long sentTick = -1;
    private boolean completed;

    public BatchReceipt(long epoch, long batch) {
        if (epoch <= 0 || batch <= 0) throw new IllegalArgumentException("batch identity");
        this.epoch = epoch;
        this.batch = batch;
    }

    public synchronized void sent(long tick) {
        if (!completed && sentTick < 0) sentTick = tick;
    }

    public synchronized boolean acknowledge(long epoch, long batch) {
        if (completed || sentTick < 0 || this.epoch != epoch || this.batch != batch) return false;
        completed = true;
        return true;
    }

    public synchronized boolean expire(long tick, long timeout) {
        if (completed || sentTick < 0 || tick - sentTick < timeout) return false;
        completed = true;
        return true;
    }

    public synchronized boolean sent() {
        return sentTick >= 0;
    }
}
