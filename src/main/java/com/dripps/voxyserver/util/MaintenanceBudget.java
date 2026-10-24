package com.dripps.voxyserver.util;

/** Global (not per player) fixed-window allowance. Call only on the streaming worker. */
public final class MaintenanceBudget {
    private long windowStart = Long.MIN_VALUE;
    private int used;
    private long usedNanos;

    public boolean tryAcquire(long tick, int intervalTicks, int limit) {
        return tryAcquire(tick, intervalTicks, limit, Long.MAX_VALUE);
    }

    public boolean tryAcquire(long tick, int intervalTicks, int limit, long maxNanos) {
        if (intervalTicks < 1 || limit < 1)
            throw new IllegalArgumentException("invalid maintenance budget");
        if (windowStart == Long.MIN_VALUE
                || tick < windowStart
                || tick - windowStart >= intervalTicks) {
            windowStart = tick;
            used = 0;
            usedNanos = 0;
        }
        if (used >= limit || usedNanos >= maxNanos) return false;
        used++;
        return true;
    }

    public void recordNanos(long elapsed) {
        usedNanos += Math.max(0L, elapsed);
    }
}
