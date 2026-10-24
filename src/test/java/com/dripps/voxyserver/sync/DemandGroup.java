package com.dripps.voxyserver.sync;

import me.cortex.voxy.common.world.WorldEngine;

/**
 * Fixed 4x4x4 groups within one native Voxy LOD. Never borrows the LOD bits for dimension identity.
 */
public final class DemandGroup {
    public static final int CELLS = 64;

    private DemandGroup() {}

    public static boolean valid(long key) {
        return (key & 15) == 0 && WorldEngine.getLevel(key) <= WorldEngine.MAX_LOD_LAYER;
    }

    public static boolean validGroup(long group) {
        return valid(group) && of(node(group, 0)) == group && of(node(group, 63)) == group;
    }

    public static long of(long node) {
        return WorldEngine.getWorldSectionId(
                WorldEngine.getLevel(node),
                WorldEngine.getX(node) >> 2,
                WorldEngine.getY(node) >> 2,
                WorldEngine.getZ(node) >> 2);
    }

    public static int slot(long node) {
        return (WorldEngine.getX(node) & 3)
                | ((WorldEngine.getZ(node) & 3) << 2)
                | ((WorldEngine.getY(node) & 3) << 4);
    }

    public static long node(long group, int slot) {
        if (slot < 0 || slot >= CELLS) throw new IllegalArgumentException("invalid group slot");
        return WorldEngine.getWorldSectionId(
                WorldEngine.getLevel(group),
                (WorldEngine.getX(group) << 2) + (slot & 3),
                (WorldEngine.getY(group) << 2) + (slot >> 4),
                (WorldEngine.getZ(group) << 2) + ((slot >> 2) & 3));
    }

    public static boolean allowed(
            long node,
            int centerXBlocks,
            int centerZBlocks,
            int radiusChunks,
            int minYBlocks,
            int maxYBlocks) {
        if (!valid(node) || radiusChunks < 0 || radiusChunks > 4096) return false;
        long edge = 32L << WorldEngine.getLevel(node);
        long x = (long) WorldEngine.getX(node) * edge, z = (long) WorldEngine.getZ(node) * edge;
        long y = (long) WorldEngine.getY(node) * edge;
        long radius = (long) radiusChunks * 16;
        return x <= (long) centerXBlocks + radius
                && x + edge > (long) centerXBlocks - radius
                && z <= (long) centerZBlocks + radius
                && z + edge > (long) centerZBlocks - radius
                && y < maxYBlocks
                && y + edge > minYBlocks;
    }

    /**
     * Membership and slot order are part of the proof; zero hashes are unknown, never equality
     * evidence.
     */
    public static long digest(long mask, long[] hashes) {
        if (hashes.length != CELLS)
            throw new IllegalArgumentException("expected 64 semantic hashes");
        long h = 0x44454D414E443035L ^ mask;
        for (int i = 0; i < CELLS; i++)
            if ((mask & (1L << i)) != 0) {
                if (hashes[i] == 0) return 0;
                h ^= Long.rotateLeft(hashes[i], i) ^ (i * 0x9e3779b97f4a7c15L);
                h = Long.rotateLeft(h, 27) * 0x94d049bb133111ebL;
            }
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        return h == 0 ? 1 : h;
    }
}
