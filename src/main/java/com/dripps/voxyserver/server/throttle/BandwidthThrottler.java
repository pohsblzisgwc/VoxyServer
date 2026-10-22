package com.dripps.voxyserver.server.throttle;

import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;

/**
 * Token-bucket bandwidth limiter and distance-based priority scheduler.
 * Enforces per-player egress bandwidth limits (bytes/sec) to smooth out traffic spikes.
 */
public class BandwidthThrottler {
    public static final BandwidthThrottler INSTANCE = new BandwidthThrottler();

    private final ConcurrentHashMap<UUID, TokenBucket> playerBuckets = new ConcurrentHashMap<>();

    public static class TokenBucket {
        private final long bytesPerSecond;
        private final long maxBurst;
        private long availableTokens;
        private long lastRefillTick = -1;

        public TokenBucket(long bytesPerSecond) {
            this.bytesPerSecond = bytesPerSecond;
            this.maxBurst = Math.max(bytesPerSecond / 2, 65536); // At most 0.5s burst
            this.availableTokens = this.maxBurst / 2;
        }

        public synchronized void refill(long currentTick) {
            if (lastRefillTick < 0) {
                lastRefillTick = currentTick;
                return;
            }
            long deltaTicks = currentTick - lastRefillTick;
            if (deltaTicks > 0) {
                long tokensToAdd = (bytesPerSecond * deltaTicks) / 20;
                availableTokens = Math.min(maxBurst, availableTokens + tokensToAdd);
                lastRefillTick = currentTick;
            }
        }

        public synchronized boolean tryAcquire(int bytes) {
            if (availableTokens >= bytes || availableTokens > 0) {
                availableTokens -= bytes;
                return true;
            }
            return false;
        }

        public synchronized long getAvailableTokens() {
            return availableTokens;
        }
    }

    public void registerPlayer(UUID uuid, long bytesPerSecond) {
        playerBuckets.put(uuid, new TokenBucket(bytesPerSecond));
    }

    public void unregisterPlayer(UUID uuid) {
        playerBuckets.remove(uuid);
    }

    public void tick(UUID uuid, long currentTick) {
        TokenBucket bucket = playerBuckets.get(uuid);
        if (bucket != null) {
            bucket.refill(currentTick);
        }
    }

    public boolean canSend(UUID uuid, int bytes) {
        TokenBucket bucket = playerBuckets.get(uuid);
        if (bucket == null) return true;
        return bucket.tryAcquire(bytes);
    }

    /**
     * Determines whether a section at relative distance dx, dz and distance in sections
     * should be delayed based on distance-based trickle feed.
     *
     * @param distSections distance from player in sections (1 section = 32 blocks = 2 chunks)
     * @param sectionCounter sequence index of section processed in this tick
     * @return true if streaming should proceed, false if it should be deferred (trickled)
     */
    public boolean checkDistancePacing(int distSections, int sectionCounter, boolean enablePacing) {
        if (!enablePacing) return true;
        // Near (<= 16 sections, i.e. <= 32 chunks): stream immediately
        if (distSections <= 16) {
            return true;
        }
        // Mid-distance (16 ~ 48 sections, i.e. 32 ~ 96 chunks): pace down
        if (distSections <= 48) {
            return (sectionCounter % 2 == 0); // half rate
        }
        // Far distance (> 48 sections, i.e. > 96 chunks): trickle feed (1 per 4 items)
        return (sectionCounter % 4 == 0);
    }
}
