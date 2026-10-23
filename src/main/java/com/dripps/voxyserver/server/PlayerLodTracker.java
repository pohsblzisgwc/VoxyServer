package com.dripps.voxyserver.server;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import com.dripps.voxyserver.network.ManifestScope;
import com.dripps.voxyserver.network.LODManifestPayload;
import com.dripps.voxyserver.network.LODManifestAckPayload;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
    private record PacedEntry(long key, long releaseTick) {}
    private final Map<Long, PacedEntry> deferredPacedSections = new LinkedHashMap<>();
    private final Long2LongOpenHashMap clientRegionHashes = new Long2LongOpenHashMap();
    public static final int MAX_MANIFEST_ENTRIES = 65_536;
    public static final long MANIFEST_IDLE_TICKS = 2400, MANIFEST_MAX_TICKS = 12_000;
    private ManifestScope manifestScope = new ManifestScope(0, 0, 2048);
    private int manifestDimensionOrdinal;
    private boolean manifestOpen;
    private long manifestStartedTick, manifestProgressTick;
    private final Long2LongOpenHashMap stagedHashes = new Long2LongOpenHashMap();
    private final LongOpenHashSet manifestConflicts = new LongOpenHashSet();
    private record ManifestPage(long[] keys, long[] hashes, int status) {}
    private final List<ManifestPage> manifestPages = new ArrayList<>();
    private ManifestPage completedManifestPage;
    private long completedManifestPageId = -1;
    private long manifestsCompleted, manifestsFailed, manifestTimeouts, manifestDurationTicks, manifestCompletedTick;
    private long batchesSucceeded, batchesFailed, ackTimeouts, retries, dirtyRecoveryTicks, dirtyRecoveries;
    private final Map<Long, Long> dirtySince = new HashMap<>();
    private long nextManifestRefreshTick;
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
    private long heldScanKey = NO_SECTION_KEY;
    private long nextFullRescanTick;

    public record StreamSession(Identifier dimension, long streamEpoch,
                                long manifestRequestId, boolean manifestRequired) {}

    public record Delivery(long streamEpoch, long batchId) {}

    private static final class OutstandingBatch {
        final long[] sectionKeys, hashes;
        final int bytes;
        final long createdTick;
        boolean timeoutCounted;
        final LongOpenHashSet invalidated = new LongOpenHashSet();
        OutstandingBatch(long[] keys, long[] hashes, int bytes, long tick) {
            this.sectionKeys = keys.clone(); this.hashes = hashes.clone(); this.bytes = bytes; this.createdTick = tick;
        }
    }

    public record SyncStats(long manifestsCompleted, long manifestsFailed, long manifestTimeouts,
                            long manifestDurationTicks, long manifestCompletedTick, int stagedEntries, long batchesSucceeded,
                            long batchesFailed, long ackTimeouts, long retries, long dirtyRecoveryTicks,
                            long dirtyRecoveries, int outstandingBatches, long outstandingBytes) {
        public double deliveryFailureRate() {
            long total = batchesSucceeded + batchesFailed;
            return total == 0 ? 0 : (double) batchesFailed / total;
        }
    }
    public synchronized SyncStats syncStats() {
        return new SyncStats(manifestsCompleted, manifestsFailed, manifestTimeouts, manifestDurationTicks, manifestCompletedTick,
                stagedHashes.size(), batchesSucceeded, batchesFailed, ackTimeouts, retries,
                dirtyRecoveryTicks, dirtyRecoveries, outstandingBatches.size(), outstandingBytes);
    }

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

    public synchronized boolean hasKnownSection(long sectionKey) {
        return acknowledgedSectionHashes.containsKey(sectionKey);
    }

    public synchronized boolean isDirtyRecovery(long sectionKey) { return dirtySince.containsKey(sectionKey); }

    public synchronized boolean hasOutstanding(long sectionKey) {
        return sectionOwners.containsKey(sectionKey);
    }

    /** Stages an inventory transaction. Only a contiguous COMPLETE may publish its claims. */
    public synchronized int acceptManifestPage(Identifier dimension, long epoch, long requestId,
            int dimensionOrdinal, long[] keys, long[] hashes, int status, long pageId, long tick, boolean reuse) {
        if (!matchesManifestLocked(dimension, epoch, requestId)) return LODManifestAckPayload.ABORT;
        expireManifest(tick);
        if (dimensionOrdinal != manifestDimensionOrdinal) return abortManifest();
        if (keys.length != hashes.length || keys.length > LODManifestPayload.MAX_ENTRIES || pageId < 0)
            return abortManifest();
        if (status == LODManifestPayload.FAILED || status == LODManifestPayload.CANCELED)
            return abortManifest();
        if (!manifestOpen) {
            return completedManifestPage != null && pageId == completedManifestPageId
                    && completedManifestPage.status == status
                    && Arrays.equals(completedManifestPage.keys, keys) && Arrays.equals(completedManifestPage.hashes, hashes)
                    ? LODManifestAckPayload.ACCEPTED : LODManifestAckPayload.ABORT;
        }
        if (pageId < manifestPages.size()) {
            ManifestPage previous = manifestPages.get((int) pageId);
            return previous.status == status && Arrays.equals(previous.keys, keys) && Arrays.equals(previous.hashes, hashes)
                    ? LODManifestAckPayload.ACCEPTED : abortManifest();
        }
        if (pageId != manifestPages.size()) return LODManifestAckPayload.RETRY;
        if (status != LODManifestPayload.PART && status != LODManifestPayload.COMPLETE) return abortManifest();
        if (manifestPages.size() >= 1024) return abortManifest();
        for (int i = 0; i < keys.length; i++) {
            if (!manifestScope.contains(keys[i]) || hashes[i] == 0) return abortManifest();
            long composite = LodStreamingService.composeSectionKey(dimensionOrdinal, keys[i]);
            if (stagedHashes.containsKey(composite) && stagedHashes.get(composite) != hashes[i]) return abortManifest();
            stagedHashes.put(composite, hashes[i]);
            if (stagedHashes.size() > MAX_MANIFEST_ENTRIES) return abortManifest();
        }
        manifestPages.add(new ManifestPage(keys.clone(), hashes.clone(), status));
        manifestProgressTick = tick;
        if (status == LODManifestPayload.COMPLETE) {
            invalidateManifestScope();
            for (var entry : stagedHashes.long2LongEntrySet()) {
                long key = entry.getLongKey();
                // Deliveries overlapping the observation window are conservatively re-sent.
                // Their late ACKs cannot overwrite this inventory, including absent entries.
                if (reuse && !manifestConflicts.contains(key)) acknowledgedSectionHashes.put(key, entry.getLongValue());
                deferDirtySection(key, LodStreamingService.extractSectionKey(key));
            }
            stagedHashes.clear(); manifestConflicts.clear();
            // Only COMPLETE can legitimately be retried after commit (one page in flight).
            completedManifestPageId = pageId;
            completedManifestPage = manifestPages.getLast();
            manifestPages.clear();
            manifestOpen = false; awaitingManifestDim = null;
            manifestsCompleted++; manifestDurationTicks = tick - manifestStartedTick; manifestCompletedTick = tick;
            retryNotBeforeTick = 0; resetScanStateLocked();
        }
        return LODManifestAckPayload.ACCEPTED;
    }

    private int abortManifest() {
        if (manifestOpen) {
            invalidateManifestScope();
            manifestsFailed++;
            manifestOpen = false; awaitingManifestDim = null;
            stagedHashes.clear(); manifestPages.clear(); manifestConflicts.clear();
            resetScanStateLocked();
        }
        return LODManifestAckPayload.ABORT;
    }

    private boolean inManifestScope(long composite) {
        return LodStreamingService.extractSectionDimOrdinal(composite) == manifestDimensionOrdinal
                && manifestScope.contains(LodStreamingService.extractSectionKey(composite));
    }

    private void invalidateManifestScope() {
        var iterator = acknowledgedSectionHashes.keySet().iterator();
        while (iterator.hasNext()) {
            long key = iterator.nextLong();
            if (inManifestScope(key)) {
                iterator.remove();
                deferDirtySection(key, LodStreamingService.extractSectionKey(key));
            }
        }
        for (var owner : outstandingBatches.entrySet()) {
            for (long key : owner.getValue().sectionKeys) if (inManifestScope(key)) {
                owner.getValue().invalidated.add(key);
                if (sectionOwners.get(key) == owner.getKey()) sectionOwners.remove(key);
                deferDirtySection(key, LodStreamingService.extractSectionKey(key));
            }
        }
    }

    public synchronized void expireManifest(long tick) {
        if (manifestOpen && (tick - manifestProgressTick >= MANIFEST_IDLE_TICKS
                || tick - manifestStartedTick >= MANIFEST_MAX_TICKS)) {
            manifestTimeouts++;
            abortManifest();
        }
    }

    public synchronized boolean containsManifestKey(long key) { return manifestScope.contains(key); }
    public synchronized ManifestScope manifestScope() { return manifestScope; }
    public synchronized boolean isBatchSendable(long epoch, long batchId) {
        OutstandingBatch batch = outstandingBatches.get(batchId);
        return epoch == streamEpoch && batch != null && batch.invalidated.isEmpty();
    }

    public synchronized boolean applyManifestBatch(Identifier dimension, long epoch, long requestId,
            int ordinal, long[] keys, long[] hashes) {
        return acceptManifestPage(dimension, epoch, requestId, ordinal, keys, hashes, LODManifestPayload.PART,
                manifestPages.size(), manifestProgressTick, true) == LODManifestAckPayload.ACCEPTED;
    }

    public synchronized StreamSession beginSession(Identifier dimension, boolean required, long deadline,
                                                   ManifestScope scope, int dimensionOrdinal) {
        manifestScope = scope;
        manifestDimensionOrdinal = dimensionOrdinal;
        return beginSession(dimension, required, deadline);
    }

    public synchronized StreamSession beginSession(Identifier dimension, boolean manifestRequired,
                                                   long deadlineTick) {
        clearOutstandingLocked();
        manifestOpen = false;
        streamEpoch = com.dripps.voxyserver.util.SessionEpoch.next();
        acknowledgedSectionHashes.clear();
        clientRegionHashes.clear();
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
        startManifest(deadlineTick - 60);
        if (!manifestRequired) { manifestOpen = false; awaitingManifestDim = null; }
        return new StreamSession(dimension, streamEpoch, manifestRequestId, manifestRequired);
    }

    public synchronized StreamSession currentSession() {
        return new StreamSession(activeDimension, streamEpoch, manifestRequestId, awaitingManifestDim != null);
    }

    public synchronized void recordManifestArea(int x, int z, int radius) {
        recordManifestArea(x, z, radius, 0L, 600L);
    }

    public synchronized void recordManifestArea(int x, int z, int radius, long tick, long refreshTicks) {
        nextManifestRefreshTick = tick + refreshTicks;
        manifestCenterX = x;
        manifestCenterZ = z;
        manifestRadius = radius;
    }

    public synchronized boolean needsManifestRefresh(int x, int z, int radius, long tick) {
        if (tick < nextManifestRefreshTick || manifestOpen) return false;
        int threshold = Math.max(4, radius >> 1);
        return manifestRadius != radius
                || Math.abs((long) x - manifestCenterX) >= threshold
                || Math.abs((long) z - manifestCenterZ) >= threshold;
    }

    public synchronized StreamSession refreshManifest(long deadlineTick, ManifestScope scope, int ordinal) {
        if (manifestOpen) abortManifest();
        manifestScope = scope;
        manifestDimensionOrdinal = ordinal;
        return refreshManifest(deadlineTick);
    }

    public synchronized StreamSession refreshManifest(long deadlineTick) {
        manifestRequestId = nextPositive(manifestRequestId);
        awaitingManifestDim = activeDimension;
        manifestDeadlineTick = deadlineTick;
        startManifest(deadlineTick - 60);
        return currentSession();
    }

    private void startManifest(long tick) {
        manifestOpen = true; manifestStartedTick = tick; manifestProgressTick = tick;
        completedManifestPage = null; completedManifestPageId = -1;
        stagedHashes.clear(); manifestPages.clear(); manifestConflicts.clear();
        for (OutstandingBatch batch : outstandingBatches.values())
            for (long key : batch.sectionKeys) if (inManifestScope(key)) manifestConflicts.add(key);
    }

    public synchronized void deferDirtySection(long compositeKey, long sectionKey, long tick) {
        if (dirtySince.size() < 4096) dirtySince.putIfAbsent(compositeKey, tick);
        deferDirtySection(compositeKey, sectionKey);
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

    public synchronized boolean deferPacedSection(long compositeKey, long sectionKey) {
        return deferPacedSection(compositeKey, sectionKey, 0L);
    }

    public synchronized boolean deferPacedSection(long compositeKey, long sectionKey, long tick) {
        if (deferredPacedSections.size() < 4096 || deferredPacedSections.containsKey(compositeKey)) {
            deferredPacedSections.put(compositeKey, new PacedEntry(sectionKey, tick + 2));
            return true;
        }
        heldScanKey = sectionKey;
        return false;
    }

    public synchronized long pollDeferredPacedSection() {
        return pollDeferredPacedSection(Long.MAX_VALUE);
    }

    public synchronized long pollDeferredPacedSection(long tick) {
        var iterator = deferredPacedSections.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (sectionOwners.containsKey(entry.getKey()) || entry.getValue().releaseTick() > tick) continue;
            long key = entry.getValue().key();
            iterator.remove();
            return key;
        }
        return NO_SECTION_KEY;
    }

    public synchronized boolean hasDeferredPacedSections() {
        return !deferredPacedSections.isEmpty();
    }

    public synchronized boolean hasReadyDeferredWork(long tick) {
        if (heldScanKey != NO_SECTION_KEY) return true;
        for (long key : deferredDirtySections.keySet()) if (!sectionOwners.containsKey(key)) return true;
        for (var entry : deferredPacedSections.entrySet())
            if (entry.getValue().releaseTick <= tick && !sectionOwners.containsKey(entry.getKey())) return true;
        return false;
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
        if (manifestOpen) {
            for (long key : sectionKeys) if (inManifestScope(key)) manifestConflicts.add(key);
            if (manifestConflicts.size() > MAX_MANIFEST_ENTRIES) abortManifest();
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
            batchesSucceeded++;
            for (int i = 0; i < batch.sectionKeys.length; i++) {
                long key = batch.sectionKeys[i];
                if (batch.invalidated.contains(key)) continue;
                acknowledgedSectionHashes.put(key, batch.hashes[i]);
                Long since = dirtySince.remove(key);
                if (since != null) { dirtyRecoveryTicks += Math.max(0, tick - since); dirtyRecoveries++; }
            }
        } else {
            batchesFailed++; retries += batch.sectionKeys.length;
            for (long key : batch.sectionKeys) deferDirtySection(key, LodStreamingService.extractSectionKey(key), tick);
            retryNotBeforeTick = Math.max(retryNotBeforeTick, tick + 100L);
            resetScanStateLocked();
        }
        return true;
    }

    public synchronized boolean hasTimedOutBatch(long tick, long timeoutTicks) {
        boolean timedOut = false;
        for (OutstandingBatch batch : outstandingBatches.values()) {
            if (tick - batch.createdTick >= timeoutTicks) {
                if (!batch.timeoutCounted) { ackTimeouts++; batch.timeoutCounted = true; }
                timedOut = true;
            }
        }
        return timedOut;
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
        return acceptManifestPage(dimension, epoch, requestId, manifestDimensionOrdinal, new long[0], new long[0],
                LODManifestPayload.COMPLETE, manifestPages.size(), manifestProgressTick, true) == LODManifestAckPayload.ACCEPTED;
    }

    public synchronized boolean failManifest(Identifier dimension, long epoch, long requestId) {
        if (!matchesManifestLocked(dimension, epoch, requestId)) return false;
        abortManifest(); return true;
    }

    public synchronized void reset() {
        acknowledgedSectionHashes.clear();
        clientRegionHashes.clear();
        clearOutstandingLocked();
        awaitingManifestDim = null;
        manifestOpen = false; stagedHashes.clear(); manifestPages.clear(); manifestConflicts.clear();
        completedManifestPage = null; completedManifestPageId = -1;
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
        expireManifest(currentTick);
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

    public synchronized boolean matchesManifest(Identifier dimension, long epoch, long requestId) {
        return matchesManifestLocked(dimension, epoch, requestId);
    }

    private boolean matchesManifestLocked(Identifier dimension, long epoch, long requestId) {
        return dimension.equals(activeDimension)
                && streamEpoch == epoch
                && manifestRequestId == requestId;
    }

    private void clearOutstandingLocked() {
        deferredDirtySections.clear();
        dirtySince.clear();
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

    public synchronized boolean prepareScanWork(int x, int z, int radius, int minY, int maxY,
                                                long tick, long idleTicks) {
        return prepareScan(x, z, radius, minY, maxY, tick, idleTicks) || hasReadyDeferredWork(tick);
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
        if (heldScanKey != NO_SECTION_KEY) {
            long held = heldScanKey; heldScanKey = NO_SECTION_KEY; return held;
        }
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
        heldScanKey = NO_SECTION_KEY;
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
