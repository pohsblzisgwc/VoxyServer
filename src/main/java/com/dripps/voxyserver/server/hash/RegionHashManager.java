package com.dripps.voxyserver.server.hash;

import com.dripps.voxyserver.util.FastHash;
import me.cortex.voxy.common.world.WorldEngine;
import net.minecraft.resources.Identifier;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages 64-bit Merkle-style Region hashes.
 * Each Region corresponds to a 16x16 WorldSection horizontal grid (512x512 blocks),
 * matching standard Minecraft Anvil region boundaries.
 *
 * Hash states explicitly distinguish UNCOMPUTED, PARTIAL, COMPLETE, and DIRTY states.
 * 64-bit non-cryptographic hashes are used opportunistically for bandwidth reduction;
 * server authoritative state is always maintained and dirty sections invalidate hashes immediately.
 */
public class RegionHashManager {
    public static final RegionHashManager INSTANCE = new RegionHashManager();

    public enum HashState {
        UNCOMPUTED,
        PARTIAL,
        COMPLETE,
        DIRTY
    }

    public static class RegionEntry {
        private volatile HashState state = HashState.UNCOMPUTED;
        private volatile long hash = 0L;
        private final ConcurrentHashMap<Long, Long> sectionHashes = new ConcurrentHashMap<>();

        public HashState getState() {
            return state;
        }

        public long getHash() {
            return hash;
        }

        public int getSectionCount() {
            return sectionHashes.size();
        }

        public synchronized void updateSection(long sectionKey, long sectionHash) {
            Long prevHash = sectionHashes.put(sectionKey, sectionHash);
            if (prevHash == null || prevHash != sectionHash) {
                long base = hash;
                if (prevHash != null) {
                    base = FastHash.combine(base, sectionKey, prevHash); // remove old contribution
                }
                base = FastHash.combine(base, sectionKey, sectionHash); // fold new contribution
                this.hash = (base == 0L) ? 1L : base; // reserve 0L for uncomputed
                this.state = HashState.PARTIAL;
            }
        }

        public synchronized void markDirty() {
            this.state = HashState.DIRTY;
        }

        public synchronized void markComplete() {
            if (this.state != HashState.DIRTY && !sectionHashes.isEmpty()) {
                this.state = HashState.COMPLETE;
            }
        }
    }

    // dimension -> (regionKey -> RegionEntry)
    private final ConcurrentHashMap<Identifier, com.dripps.voxyserver.util.BoundedLruCache<Long, RegionEntry>> dimensionRegions = new ConcurrentHashMap<>();

    private RegionHashManager() {}

    /**
     * Stable, coordinate-based 64-bit region key (regX << 32 | regZ).
     * Guaranteed consistent across client and server without relying on internal dimension ordinals.
     */
    public static long getRegionKey(int regX, int regZ) {
        return (((long) regX) << 32) | (regZ & 0xFFFFFFFFL);
    }

    public static int getRegionX(long regionKey) {
        return (int) (regionKey >> 32);
    }

    public static int getRegionZ(long regionKey) {
        return (int) regionKey;
    }

    public static long getRegionKeyForSection(long sectionKey) {
        int secX = WorldEngine.getX(sectionKey);
        int secZ = WorldEngine.getZ(sectionKey);
        return getRegionKey(secX >> 4, secZ >> 4);
    }

    private com.dripps.voxyserver.util.BoundedLruCache<Long, RegionEntry> getDimMap(Identifier dimension) {
        return dimensionRegions.computeIfAbsent(dimension, k -> new com.dripps.voxyserver.util.BoundedLruCache<>(2048));
    }

    // The caller supplies the shared semantic content hash, never raw mapper IDs.
    public void updateSectionHash(Identifier dimension, long sectionKey, long sectionHash) {
        if (dimension == null) return;
        if (sectionHash == 0L) sectionHash = 1L;

        long regKey = getRegionKeyForSection(sectionKey);
        com.dripps.voxyserver.util.BoundedLruCache<Long, RegionEntry> dimMap = getDimMap(dimension);
        RegionEntry entry = dimMap.compute(regKey, (k, old) -> old == null ? new RegionEntry() : old);
        entry.updateSection(sectionKey, sectionHash);
    }

    public void invalidateSection(Identifier dimension, long sectionKey) {
        if (dimension == null) return;
        com.dripps.voxyserver.util.BoundedLruCache<Long, RegionEntry> dimMap = dimensionRegions.get(dimension);
        if (dimMap == null) return;
        long regKey = getRegionKeyForSection(sectionKey);
        RegionEntry entry = dimMap.get(regKey);
        if (entry != null) {
            entry.markDirty();
        }
    }

    public long getRegionHash(Identifier dimension, long regionKey) {
        if (dimension == null) return 0L;
        com.dripps.voxyserver.util.BoundedLruCache<Long, RegionEntry> dimMap = dimensionRegions.get(dimension);
        if (dimMap == null) return 0L;
        RegionEntry entry = dimMap.get(regionKey);
        if (entry == null || entry.getState() != HashState.COMPLETE) {
            return 0L;
        }
        return entry.getHash();
    }

    public HashState getRegionState(Identifier dimension, long regionKey) {
        if (dimension == null) return HashState.UNCOMPUTED;
        com.dripps.voxyserver.util.BoundedLruCache<Long, RegionEntry> dimMap = dimensionRegions.get(dimension);
        if (dimMap == null) return HashState.UNCOMPUTED;
        RegionEntry entry = dimMap.get(regionKey);
        return entry == null ? HashState.UNCOMPUTED : entry.getState();
    }

    public boolean isRegionSynced(Identifier dimension, long regionKey, long clientHash) {
        if (clientHash == 0L || dimension == null) return false;
        com.dripps.voxyserver.util.BoundedLruCache<Long, RegionEntry> dimMap = dimensionRegions.get(dimension);
        if (dimMap == null) return false;
        RegionEntry entry = dimMap.get(regionKey);
        if (entry == null) return false;
        if (entry.getState() != HashState.COMPLETE) {
            return false;
        }
        return entry.getHash() != 0L && entry.getHash() == clientHash;
    }

    public void clearDimension(Identifier dimension) {
        if (dimension != null) {
            dimensionRegions.remove(dimension);
        }
    }

    public void clearAll() {
        dimensionRegions.clear();
    }
}
