package com.dripps.voxyserver.util;

/**
 * High-performance 64-bit non-cryptographic hash for voxel sections and hierarchical regions.
 *
 * <p>SECURITY & INTEGRITY NOTICE:
 * This hash combines FNV-1a 64-bit mixing with a Murmur3 64-bit finalizer. It is explicitly
 * NON-CRYPTOGRAPHIC and intended solely for opportunistic bandwidth deduplication. It is NOT
 * a cryptographic authentication mechanism. Server-authoritative world data is never modified
 * by client-reported hashes, and server-side block edits immediately invalidate hashes to prevent
 * desynchronization.
 */
public final class FastHash {
    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private FastHash() {}

    /**
     * Hashes a section's 32,768 long mapping IDs into a single 64-bit hash value.
     */
    public static long hashVoxelData(long[] data) {
        if (data == null || data.length == 0) return 0L;
        long hash = FNV_OFFSET_BASIS;
        for (long val : data) {
            hash ^= val;
            hash *= FNV_PRIME;
        }
        // Avalanche step (Murmur3 64-bit finalizer) to ensure uniform bit distribution
        hash ^= (hash >>> 33);
        hash *= 0xff51afd7ed558ccdL;
        hash ^= (hash >>> 33);
        hash *= 0xc4ceb9fe1a85ec53L;
        hash ^= (hash >>> 33);
        return hash;
    }

    /**
     * Combines multiple section hashes into a higher-level region hash (Merkle branch).
     */
    public static long combine(long parentHash, long childKey, long childHash) {
        long mixed = childKey ^ (childHash * FNV_PRIME);
        mixed ^= (mixed >>> 30) * 0xbf58476d1ce4e5b9L;
        mixed ^= (mixed >>> 27) * 0x94d049bb133111ebL;
        mixed ^= (mixed >>> 31);
        return parentHash ^ mixed;
    }
}
