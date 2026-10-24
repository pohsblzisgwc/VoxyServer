package com.dripps.voxyserver.server.background;

import com.dripps.voxyserver.config.VoxyServerConfig;

/** Wall-clock token allowance, capped to one nominal tick's batch and a fixed minute's total. */
public final class GenerationRateBudget {
    private static final long MINUTE = 60_000_000_000L;
    private boolean initialized;
    private int rate, issued;
    private long window, lastRefill;
    private long credit;

    private void refill(int limit, long now) {
        if (limit < 1 || limit > VoxyServerConfig.MAX_BACKGROUND_CHUNKS_PER_MINUTE)
            throw new IllegalArgumentException("invalid background rate");
        int capacity = Math.max(1, (limit + 1199) / 1200);
        if (!initialized) {
            initialized = true;
            window = now;
            lastRefill = now;
            credit = capacity * MINUTE;
            rate = limit;
            return;
        }
        if (now < lastRefill) {
            lastRefill = now;
            window = now;
            issued = 0;
            credit = 0;
        }
        if (now - window >= MINUTE) {
            window = now;
            issued = 0;
        }
        credit =
                Math.min(
                        capacity * MINUTE,
                        credit + Math.min(MINUTE, now - lastRefill) * (long) rate);
        lastRefill = now;
        rate = limit;
    }

    public boolean tryAcquire(int limit, long now) {
        refill(limit, now);
        if (issued >= limit || credit < MINUTE) return false;
        issued++;
        credit -= MINUTE;
        return true;
    }

    public int allowance(int limit, long now) {
        refill(limit, now);
        return Math.max(0, Math.min(limit - issued, (int) (credit / MINUTE)));
    }
}
