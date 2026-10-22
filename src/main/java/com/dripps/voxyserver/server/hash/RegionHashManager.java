package com.dripps.voxyserver.server.hash;

import com.dripps.voxyserver.util.FastHash;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import me.cortex.voxy.common.world.WorldEngine;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages 64-bit Merkle-style Region hashes.
 * Each Region corresponds to a 16x16 WorldSection grid (512x512 blocks),
 * matching standard Minecraft Anvil region boundaries.
 */
public class RegionHashManager {
    public static final RegionHashManager INSTANCE = new RegionHashManager();

    // compositeSectionKey -> 64-bit section hash
    private final ConcurrentHashMap<Long, Long> sectionHashes = new ConcurrentHashMap<>();
    // regionKey -> 64-bit region Merkle root hash
    private final ConcurrentHashMap<Long, Long> regionHashes = new ConcurrentHashMap<>();

    private RegionHashManager() {}

    public static long getRegionKey(int dimOrdinal, int secX, int secZ) {
        int regX = secX >> 4;
        int regZ = secZ >> 4;
        return ((long)(dimOrdinal & 0xF) << 60) | (((long)(regX & 0xFFFFFFF)) << 28) | ((long)(regZ & 0xFFFFFFFL));
    }

    public static long getRegionKeyForSection(int dimOrdinal, long sectionKey) {
        int secX = WorldEngine.getX(sectionKey);
        int secZ = WorldEngine.getZ(sectionKey);
        return getRegionKey(dimOrdinal, secX, secZ);
    }

    public void updateSectionHash(int dimOrdinal, long sectionKey, long[] rawVoxelData) {
        long sectionHash = FastHash.hashVoxelData(rawVoxelData);
        long compositeSecKey = sectionKey | ((long)(dimOrdinal & 0xF) << 60);
        Long prevHash = sectionHashes.put(compositeSecKey, sectionHash);

        if (prevHash == null || prevHash != sectionHash) {
            long regKey = getRegionKeyForSection(dimOrdinal, sectionKey);
            // Recompute / fold into the region hash
            regionHashes.compute(regKey, (k, currentRegHash) -> {
                long base = (currentRegHash == null) ? 0L : currentRegHash;
                // XOR diff to roll the hash efficiently
                if (prevHash != null) {
                    base = FastHash.combine(base, compositeSecKey, prevHash); // remove old
                }
                return FastHash.combine(base, compositeSecKey, sectionHash); // add new
            });
        }
    }

    public long getRegionHash(long regionKey) {
        return regionHashes.getOrDefault(regionKey, 0L);
    }

    public long getSectionHash(int dimOrdinal, long sectionKey) {
        long compositeSecKey = sectionKey | ((long)(dimOrdinal & 0xF) << 60);
        return sectionHashes.getOrDefault(compositeSecKey, 0L);
    }
}
