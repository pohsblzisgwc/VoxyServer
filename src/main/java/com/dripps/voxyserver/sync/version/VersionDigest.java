package com.dripps.voxyserver.sync.version;

/** Digest of the fixed-order sparse 32-bit version sequence. No voxel data is read. */
public final class VersionDigest {
    private VersionDigest() {}

    public static long compute(int[] versions, VersionScope scope, long root) {
        if (versions.length != 4096) throw new IllegalArgumentException("version sequence length");
        long h = 0xcbf29ce484222325L;
        boolean any = false;
        for (int i = 0; i < 4096; i++)
            if (versions[i] > 0 && (scope == null || scope.contains(VersionScope.node(root, i)))) {
                any = true;
                h = (h ^ i) * 0x100000001b3L;
                h = (h ^ (versions[i] & 0xffffffffL)) * 0x100000001b3L;
            }
        if (!any) return 0;
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        return h == 0 ? 1 : h;
    }
}
