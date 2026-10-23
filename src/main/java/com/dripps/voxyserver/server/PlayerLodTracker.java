package com.dripps.voxyserver.server;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import me.cortex.voxy.common.world.WorldEngine;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.LinkedHashMap;

public class PlayerLodTracker {
    public static final long NO_SECTION_KEY = -1L;
    private static final int MAX_OUTSTANDING_BATCHES = 32;
    private static final int MAX_OUTSTANDING_SECTIONS = 512;
    private static final long MAX_OUTSTANDING_BYTES = 64L * 1024L * 1024L;

    private final Long2LongOpenHashMap acknowledgedSectionHashes = new Long2LongOpenHashMap();
    private final Long2LongOpenHashMap sectionOwners = new Long2LongOpenHashMap();
    private final Map<Long, OutstandingBatch> outstandingBatches = new HashMap<>();
    private final Map<Long, Long> deferredDirtySections = new LinkedHashMap<>();
    private final Map<Long, Long> deferredPacedSections = new LinkedHashMap<>();
    private final Long2LongOpenHashMap clientRegionHashes = new Long2LongOpenHashMap();
    private int manifestCenterX;
    private int manifestCenterZ;
    private int manifestRadius = -1;
    private volatile boolean ready = false;
    private volatile boolean protocolOk = false;

    private Identifier awaitingManifestDim;
    private long manifestDeadlineTick;
    private long streamEpoch;
    private long manifestRequestId;
    private long nextBatchId;
    private long outstandingBytes;
    private int outstandingSections;
    private long retryNotBeforeTick;
    private volatile Identifier activeDimension;
    private int lastChunkX;
    private int lastChunkZ;

    private volatile boolean lodEnabled = true;
    private volatile int preferredRadius = -1;
    private volatile int preferredMaxSections = -1;

    private int scanCenterSecX;
    private int scanCenterSecZ;
    private int scanRadiusSections = -1;
    private int scanMinSectionY;
    private int scanMaxSectionY;
    private int scanCursorDist;
    private int scanCursorDx;
    private int scanCursorDz;
    private int scanCursorY;
    private boolean scanGeometryInitialized;
    private boolean scanCursorInitialized;
    private boolean scanExhausted;
    private long nextFullRescanTick;

    public record StreamSession(Identifier dimension, long streamEpoch,
                                long manifestRequestId, boolean manifestRequired) {}

    public record Delivery(long streamEpoch, long batchId) {}

    private record OutstandingBatch(long[] sectionKeys, long[] hashes, int bytes, long createdTick) {}

    public PlayerLodTracker() {
        this.acknowledgedSectionHashes.defaultReturnValue(0L);
        this.sectionOwners.defaultReturnValue(0L);
        this.clientRegionHashes.defaultReturnValue(0L);
    }

    public boolean isReady() {
        return ready;
    }

    public void setReady(boolean ready) {
        this.ready = ready;
    }

    public boolean isProtocolOk() {
        return protocolOk;
    }

    public void setProtocolOk(boolean protocolOk) {
        this.protocolOk = protocolOk;
    }

    public synchronized boolean hasSent(long sectionKey, long hash) {
        return acknowledgedSectionHashes.containsKey(sectionKey)
                && acknowledgedSectionHashes.get(sectionKey) == hash;
    }

    public synchronized boolean hasOutstanding(long sectionKey) {
        return sectionOwners.containsKey(sectionKey);
    }

    public synchronized boolean applyManifestBatch(Identifier dimension, long epoch, long requestId,
                                                    int dimensionOrdinal, long[] sectionKeys, long[] hashes) {
        if (!matchesManifestLocked(dimension, epoch, requestId)) return false;
        int count = Math.min(sectionKeys.length, hashes.length);
        for (int i = 0; i < count; i++) {
            long sectionKey = LodStreamingService.composeSectionKey(dimensionOrdinal, sectionKeys[i]);
            if (!acknowledgedSectionHashes.containsKey(sectionKey)) {
                acknowledgedSectionHashes.put(sectionKey, hashes[i]);
            }
        }
        return true;
    }

    public synchronized StreamSession beginSession(Identifier dimension, boolean manifestRequired,
                                                   long deadlineTick) {
        clearOutstandingLocked();
        streamEpoch = nextPositive(streamEpoch);
        nextBatchId = 0L;
        activeDimension = dimension;
        retryNotBeforeTick = 0L;
        resetScanStateLocked();
        if (manifestRequired) {
            manifestRequestId = nextPositive(manifestRequestId);
            awaitingManifestDim = dimension;
            manifestDeadlineTick = deadlineTick;
        } else {
            awaitingManifestDim = null;
        }
        return new StreamSession(dimension, streamEpoch, manifestRequestId, manifestRequired);
    }

    public synchronized StreamSession currentSession() {
        return new StreamSession(activeDimension, streamEpoch, manifestRequestId, awaitingManifestDim != null);
    }

    public synchronized void recordManifestArea(int x, int z, int radius) {
        manifestCenterX = x;
        manifestCenterZ = z;
        manifestRadius = radius;
    }

    public synchronized boolean needsManifestRefresh(int x, int z, int radius, long tick) {
        if (awaitingManifestDim != null && tick < manifestDeadlineTick) return false;
        int threshold = Math.max(4, radius >> 1);
        return manifestRadius != radius
                || Math.abs((long) x - manifestCenterX) >= threshold
                || Math.abs((long) z - manifestCenterZ) >= threshold;
    }

    public synchronized StreamSession refreshManifest(long deadlineTick) {
        manifestRequestId = nextPositive(manifestRequestId);
        awaitingManifestDim = activeDimension;
        manifestDeadlineTick = deadlineTick;
        return currentSession();
    }

    public synchronized void deferDirtySection(long compositeKey, long sectionKey) {
        if (deferredDirtySections.size() < 4096 || deferredDirtySections.containsKey(compositeKey)) {
            deferredDirtySections.put(compositeKey, sectionKey);
        } else {
            resetScanStateLocked();
        }
    }

    public synchronized long pollDeferredDirtySection() {
        var iterator = deferredDirtySections.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (sectionOwners.containsKey(entry.getKey())) continue;
            long key = entry.getValue();
            iterator.remove();
            return key;
        }
        return NO_SECTION_KEY;
    }

    public synchronized boolean hasDeferredDirtySections() {
        return !deferredDirtySections.isEmpty();
    }

    public synchronized void deferPacedSection(long compositeKey, long sectionKey) {
        if (deferredPacedSections.size() < 4096 || deferredPacedSections.containsKey(compositeKey)) {
            deferredPacedSections.put(compositeKey, sectionKey);
        }
    }

    public synchronized long pollDeferredPacedSection() {
        var iterator = deferredPacedSections.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (sectionOwners.containsKey(entry.getKey())) continue;
            long key = entry.getValue();
            iterator.remove();
            return key;
        }
        return NO_SECTION_KEY;
    }

    public synchronized boolean hasDeferredPacedSections() {
        return !deferredPacedSections.isEmpty();
    }

    public synchronized void updateClientRegionHashes(long[] keys, long[] hashes) {
        if (keys == null || hashes == null) return;
        int count = Math.min(keys.length, hashes.length);
        for (int i = 0; i < count; i++) {
            if (clientRegionHashes.size() < 16384 || clientRegionHashes.containsKey(keys[i])) {
                clientRegionHashes.put(keys[i], hashes[i]);
            }
        }
    }

    public synchronized boolean isRegionSynced(long regionKey, long serverHash) {
        if (serverHash == 0L || !clientRegionHashes.containsKey(regionKey)) return false;
        return clientRegionHashes.get(regionKey) == serverHash;
    }

    public synchronized void clearRegionHashes() {
        clientRegionHashes.clear();
    }

    public synchronized Delivery reserveBatch(Identifier dimension, long expectedEpoch,
                                              long[] sectionKeys, long[] hashes, int bytes, long tick) {
        if (!dimension.equals(activeDimension) || expectedEpoch != streamEpoch) return null;
        if (sectionKeys.length == 0 || sectionKeys.length != hashes.length || bytes <= 0) return null;
        if (outstandingBatches.size() >= MAX_OUTSTANDING_BATCHES
                || outstandingSections + sectionKeys.length > MAX_OUTSTANDING_SECTIONS
                || outstandingBytes + bytes > MAX_OUTSTANDING_BYTES) return null;
        for (long sectionKey : sectionKeys) {
            if (sectionOwners.containsKey(sectionKey)) return null;
        }
        long batchId = nextPositive(nextBatchId);
        nextBatchId = batchId;
        outstandingBatches.put(batchId, new OutstandingBatch(sectionKeys, hashes, bytes, tick));
        for (long sectionKey : sectionKeys) {
            sectionOwners.put(sectionKey, batchId);
        }
        outstandingSections += sectionKeys.length;
        outstandingBytes += bytes;
        return new Delivery(streamEpoch, batchId);
    }

    public synchronized boolean completeBatch(long epoch, long batchId, boolean success, long tick) {
        if (epoch != streamEpoch) return false;
        OutstandingBatch batch = outstandingBatches.remove(batchId);
        if (batch == null) return false;
        releaseBatchLocked(batchId, batch);
        if (success) {
            for (int i = 0; i < batch.sectionKeys.length; i++) {
                acknowledgedSectionHashes.put(batch.sectionKeys[i], batch.hashes[i]);
            }
        } else {
            retryNotBeforeTick = Math.max(retryNotBeforeTick, tick + 100L);
            resetScanStateLocked();
        }
        return true;
    }

    public synchronized boolean hasTimedOutBatch(long tick, long timeoutTicks) {
        for (OutstandingBatch batch : outstandingBatches.values()) {
            if (tick - batch.createdTick >= timeoutTicks) return true;
        }
        return false;
    }

    public synchronized int remainingSectionCredit() {
        return Math.max(0, MAX_OUTSTANDING_SECTIONS - outstandingSections);
    }

    public synchronized long streamEpoch() {
        return streamEpoch;
    }

    public synchronized boolean isCurrentDelivery(Identifier dimension, long epoch) {
        return dimension.equals(activeDimension) && epoch == streamEpoch;
    }

    public synchronized boolean isBackedOff(long tick) {
        return tick < retryNotBeforeTick;
    }

    public synchronized void deferRetry(long tick) {
        retryNotBeforeTick = Math.max(retryNotBeforeTick, tick + 20L);
    }

    public synchronized boolean completeManifest(Identifier dimension, long epoch, long requestId) {
        if (!matchesManifestLocked(dimension, epoch, requestId)) return false;
        awaitingManifestDim = null;
        retryNotBeforeTick = 0L;
        return true;
    }

    public synchronized boolean failManifest(Identifier dimension, long epoch, long requestId) {
        if (!matchesManifestLocked(dimension, epoch, requestId)) return false;
        awaitingManifestDim = null;
        resetScanStateLocked();
        return true;
    }

    public synchronized void reset() {
        acknowledgedSectionHashes.clear();
        clientRegionHashes.clear();
        clearOutstandingLocked();
        awaitingManifestDim = null;
        retryNotBeforeTick = 0L;
        resetScanStateLocked();
    }

    public synchronized void resetScanState() {
        resetScanStateLocked();
    }

    public synchronized void invalidate(long sectionKey) {
        acknowledgedSectionHashes.remove(sectionKey);
    }

    // true if the scan should be gated waiting for this dims manifest. opens the gate once the deadline passes
    public synchronized boolean isManifestGated(Identifier dimension, long currentTick) {
        if (awaitingManifestDim == null) return false;
        if (!awaitingManifestDim.equals(dimension)) return false;
        if (currentTick >= manifestDeadlineTick) {
            awaitingManifestDim = null;
            return false;
        }
        return true;
    }

    public int getLastChunkX() {
        return lastChunkX;
    }

    public int getLastChunkZ() {
        return lastChunkZ;
    }

    public void updatePosition(ServerPlayer player) {
        this.lastChunkX = player.getBlockX() >> 4;
        this.lastChunkZ = player.getBlockZ() >> 4;
    }

    public void setActiveDimension(Identifier dimension) {
        this.activeDimension = dimension;
    }

    public boolean isActiveDimension(Identifier dimension) {
        return dimension.equals(this.activeDimension);
    }

    public synchronized int sentCount() {
        return acknowledgedSectionHashes.size();
    }

    private boolean matchesManifestLocked(Identifier dimension, long epoch, long requestId) {
        return awaitingManifestDim != null
                && awaitingManifestDim.equals(dimension)
                && streamEpoch == epoch
                && manifestRequestId == requestId;
    }

    private void clearOutstandingLocked() {
        deferredDirtySections.clear();
        outstandingBatches.clear();
        sectionOwners.clear();
        outstandingBytes = 0L;
        outstandingSections = 0;
    }

    private void releaseBatchLocked(long batchId, OutstandingBatch batch) {
        for (long sectionKey : batch.sectionKeys) {
            if (sectionOwners.get(sectionKey) == batchId) {
                sectionOwners.remove(sectionKey);
            }
        }
        outstandingSections -= batch.sectionKeys.length;
        outstandingBytes -= batch.bytes;
    }

    private static long nextPositive(long value) {
        return value == Long.MAX_VALUE ? 1L : value + 1L;
    }

    public synchronized boolean prepareScan(int centerSecX, int centerSecZ,
                                            int radiusSections,
                                            int minSectionY,
                                            int maxSectionY,
                                            long currentTick,
                                            long idleRescanIntervalTicks) {
        boolean geometryChanged = !scanGeometryInitialized
                || scanRadiusSections != radiusSections
                || scanMinSectionY != minSectionY
                || scanMaxSectionY != maxSectionY;

        boolean centerChanged = !scanGeometryInitialized
                || scanCenterSecX != centerSecX
                || scanCenterSecZ != centerSecZ;

        scanCenterSecX = centerSecX;
        scanCenterSecZ = centerSecZ;
        scanRadiusSections = radiusSections;
        scanMinSectionY = minSectionY;
        scanMaxSectionY = maxSectionY;
        scanGeometryInitialized = true;

        if (maxSectionY <= minSectionY) {
            markScanExhaustedLocked(currentTick, idleRescanIntervalTicks);
            return false;
        }

        if (geometryChanged || centerChanged) {
            resetScanCursorLocked();
            scanExhausted = false;
        }

        if (scanExhausted) {
            if (currentTick < nextFullRescanTick) {
                return false;
            }
            resetScanCursorLocked();
            scanExhausted = false;
        }

        if (!scanCursorInitialized) {
            if (scanCursorDist > scanRadiusSections || scanMaxSectionY <= scanMinSectionY) {
                markScanExhaustedLocked(currentTick, idleRescanIntervalTicks);
                return false;
            }
            resetScanCursorLocked();
        }

        return true;
    }

    public synchronized long nextSectionKeyToScan(long currentTick, long idleRescanIntervalTicks) {
        if (scanExhausted || !scanGeometryInitialized) {
            return NO_SECTION_KEY;
        }

        if (!scanCursorInitialized) {
            if (scanCursorDist > scanRadiusSections || scanMaxSectionY <= scanMinSectionY) {
                markScanExhaustedLocked(currentTick, idleRescanIntervalTicks);
                return NO_SECTION_KEY;
            }
            resetScanCursorLocked();
        }

        if (scanCursorDist > scanRadiusSections || scanMaxSectionY <= scanMinSectionY) {
            markScanExhaustedLocked(currentTick, idleRescanIntervalTicks);
            return NO_SECTION_KEY;
        }

        long sectionKey = WorldEngine.getWorldSectionId(
                0,
                scanCenterSecX + scanCursorDx,
                scanCursorY,
                scanCenterSecZ + scanCursorDz
        );

        advanceScanCursorLocked();
        return sectionKey;
    }

    public boolean isLodEnabled() {
        return lodEnabled;
    }

    public void setLodEnabled(boolean enabled) {
        this.lodEnabled = enabled;
    }

    public synchronized void setPreferredRadius(int radius) {
        if (this.preferredRadius != radius) {
            this.preferredRadius = radius;
            resetScanStateLocked();
        }
    }

    public void setPreferredMaxSections(int maxSections) {
        this.preferredMaxSections = maxSections;
    }

    // returns the clients preferred radius clamped to the server max, or server default if unset
    public int getEffectiveRadius(int serverMax) {
        int max = (serverMax <= 0) ? 1024 : serverMax;
        return (preferredRadius <= 0) ? max : Math.min(preferredRadius, max);
    }

    // returns the clients preferred rate clamped to the server max, or server default if unset
    public int getEffectiveMaxSections(int serverMax) {
        int max = (serverMax <= 0) ? 100 : serverMax;
        return (preferredMaxSections <= 0) ? max : Math.min(preferredMaxSections, max);
    }

    private void resetScanStateLocked() {
        scanGeometryInitialized = false;
        scanExhausted = false;
        nextFullRescanTick = 0L;
        deferredPacedSections.clear();
        resetScanCursorLocked();
    }

    private void resetScanCursorLocked() {
        scanCursorDist = 0;
        scanCursorDx = 0;
        scanCursorDz = 0;
        scanCursorY = scanMinSectionY;
        scanCursorInitialized = true;
    }

    private void advanceScanCursorLocked() {
        scanCursorY++;
        if (scanCursorY < scanMaxSectionY) {
            return;
        }

        advanceScanColumnLocked();
    }

    private void advanceScanColumnLocked() {
        if (scanCursorDist == 0) {
            scanCursorDist = 1;
            if (scanCursorDist > scanRadiusSections) {
                scanCursorInitialized = false;
                return;
            }

            scanCursorDx = -scanCursorDist;
            scanCursorDz = -scanCursorDist;
            scanCursorY = scanMinSectionY;
            return;
        }

        while (true) {
            scanCursorDz++;
            while (scanCursorDx <= scanCursorDist) {
                while (scanCursorDz <= scanCursorDist) {
                    if (Math.abs(scanCursorDx) == scanCursorDist || Math.abs(scanCursorDz) == scanCursorDist) {
                        scanCursorY = scanMinSectionY;
                        return;
                    }
                    scanCursorDz++;
                }
                scanCursorDx++;
                scanCursorDz = -scanCursorDist;
            }

            scanCursorDist++;
            if (scanCursorDist > scanRadiusSections) {
                scanCursorInitialized = false;
                return;
            }

            scanCursorDx = -scanCursorDist;
            scanCursorDz = -scanCursorDist;
            scanCursorY = scanMinSectionY;
            return;
        }
    }

    private void markScanExhaustedLocked(long currentTick, long idleRescanIntervalTicks) {
        scanExhausted = true;
        scanCursorInitialized = false;
        nextFullRescanTick = currentTick + idleRescanIntervalTicks;
    }
}
