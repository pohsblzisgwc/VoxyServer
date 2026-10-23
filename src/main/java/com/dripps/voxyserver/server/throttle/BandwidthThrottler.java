package com.dripps.voxyserver.server.throttle;

import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Token-bucket egress bandwidth limiter, distance-based trickle scheduler,
 * and per-player bounded outbound packet queue manager.
 */
public class BandwidthThrottler {
    private static final Logger LOGGER = LoggerFactory.getLogger("voxyserver");
    public static final BandwidthThrottler INSTANCE = new BandwidthThrottler();

    public static final int MIN_BURST_BYTES = 512 * 1024; // 512 KB minimum burst capacity

    private final ConcurrentHashMap<UUID, TokenBucket> playerBuckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, PlayerOutboundQueue> playerQueues = new ConcurrentHashMap<>();

    public static class TokenBucket {
        private final long bytesPerSecond;
        private final long maxBurst;
        private long availableTokens;
        private long lastRefillNanos;
        private long lastRefillTick = -1;
        private final boolean unlimited;

        public TokenBucket(long bytesPerSecond) {
            this.bytesPerSecond = bytesPerSecond;
            if (bytesPerSecond <= 0) {
                this.unlimited = true;
                this.maxBurst = Long.MAX_VALUE;
                this.availableTokens = Long.MAX_VALUE;
                this.lastRefillNanos = System.nanoTime();
            } else {
                this.unlimited = false;
                // Burst capacity accommodates at least 2 seconds of traffic or MIN_BURST_BYTES (512 KB)
                this.maxBurst = Math.max(bytesPerSecond * 2L, (long) MIN_BURST_BYTES);
                this.availableTokens = Math.min(this.maxBurst, Math.max(bytesPerSecond, (long) MIN_BURST_BYTES));
                this.lastRefillNanos = System.nanoTime();
            }
        }

        public synchronized void refill(long currentTick) {
            if (unlimited) {
                availableTokens = Long.MAX_VALUE;
                return;
            }
            long now = System.nanoTime();
            if (lastRefillTick < 0) {
                lastRefillTick = currentTick;
                lastRefillNanos = now;
                return;
            }

            long deltaTicks = currentTick - lastRefillTick;
            if (deltaTicks > 0) {
                long cappedDeltaTicks = Math.min(deltaTicks, 200L); // cap at 10 seconds to prevent overflow
                long tokensToAdd = (bytesPerSecond * cappedDeltaTicks) / 20L;
                if (tokensToAdd > 0) {
                    availableTokens = Math.min(maxBurst, availableTokens + tokensToAdd);
                }
                lastRefillTick = currentTick;
                lastRefillNanos = now;
            }
        }

        public synchronized boolean tryAcquire(long bytes) {
            if (bytes <= 0) {
                return true;
            }
            if (unlimited) {
                return true;
            }

            // Monotonic nanosecond on-demand refill when called between ticks
            long now = System.nanoTime();
            long deltaNanos = now - lastRefillNanos;
            if (deltaNanos > 10_000_000L) { // 10ms threshold
                long cappedDelta = Math.min(deltaNanos, 10_000_000_000L);
                long tokensToAdd = (bytesPerSecond * cappedDelta) / 1_000_000_000L;
                if (tokensToAdd > 0) {
                    availableTokens = Math.min(maxBurst, availableTokens + tokensToAdd);
                    lastRefillNanos = now;
                }
            }

            if (availableTokens >= bytes) {
                availableTokens -= bytes;
                return true;
            }
            return false;
        }

        public synchronized long getAvailableTokens() {
            return availableTokens;
        }

        public long getBytesPerSecond() {
            return bytesPerSecond;
        }

        public long getMaxBurst() {
            return maxBurst;
        }

        public boolean isUnlimited() {
            return unlimited;
        }
    }

    public void registerPlayer(UUID uuid, long bytesPerSecond) {
        if (bytesPerSecond <= 0) {
            LOGGER.info("Player {} registered with unlimited bandwidth (throttling disabled)", uuid);
        }
        playerBuckets.put(uuid, new TokenBucket(bytesPerSecond));
        playerQueues.put(uuid, new PlayerOutboundQueue());
    }

    public List<PlayerOutboundQueue.QueuedPacket> unregisterPlayer(UUID uuid) {
        playerBuckets.remove(uuid);
        PlayerOutboundQueue queue = playerQueues.remove(uuid);
        if (queue != null) {
            return queue.clearAndDrain();
        }
        return Collections.emptyList();
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

    public boolean enqueuePacket(UUID uuid, PlayerOutboundQueue.QueuedPacket packet, List<PlayerOutboundQueue.QueuedPacket> evictedOut) {
        PlayerOutboundQueue queue = playerQueues.get(uuid);
        if (queue == null) return false;
        return queue.enqueue(packet, evictedOut);
    }

    public PlayerOutboundQueue.QueuedPacket peekPendingPacket(UUID uuid) {
        PlayerOutboundQueue queue = playerQueues.get(uuid);
        if (queue == null) return null;
        return queue.peekNext();
    }

    public PlayerOutboundQueue.QueuedPacket pollPendingPacket(UUID uuid) {
        PlayerOutboundQueue queue = playerQueues.get(uuid);
        if (queue == null) return null;
        return queue.pollNext();
    }

    public List<PlayerOutboundQueue.QueuedPacket> clearPlayerQueue(UUID uuid) {
        PlayerOutboundQueue queue = playerQueues.get(uuid);
        if (queue != null) {
            return queue.clearAndDrain();
        }
        return Collections.emptyList();
    }

    public List<PlayerOutboundQueue.QueuedPacket> clearDimension(UUID uuid, Identifier dim) {
        PlayerOutboundQueue queue = playerQueues.get(uuid);
        if (queue != null) {
            return queue.clearDimension(dim);
        }
        return Collections.emptyList();
    }

    public PlayerOutboundQueue getQueue(UUID uuid) {
        return playerQueues.get(uuid);
    }

    public TokenBucket getBucket(UUID uuid) {
        return playerBuckets.get(uuid);
    }

    /**
     * Determines whether a section at relative distance in sections should proceed or be deferred.
     * Near (<= 16 sections) is streamed immediately.
     * Mid-distance (17 ~ 48 sections) is trickled at 50% rate.
     * Far-distance (> 48 sections) is trickled at 25% rate.
     *
     * @param distSections distance from player in sections (1 section = 32 blocks = 2 chunks)
     * @param sequenceIndex monotonic candidate sequence index (decoupled from sent count)
     * @param enablePacing whether pacing is enabled
     * @return true if streaming should proceed, false if it should be deferred (trickled)
     */
    public boolean checkDistancePacing(int distSections, int sequenceIndex, boolean enablePacing) {
        if (!enablePacing) return true;
        if (distSections <= 16) {
            return true;
        }
        if (distSections <= 48) {
            return (sequenceIndex & 1) == 0;
        }
        return (sequenceIndex & 3) == 0;
    }
}
