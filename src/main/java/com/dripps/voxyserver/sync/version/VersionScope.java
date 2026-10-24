package com.dripps.voxyserver.sync.version;

import me.cortex.voxy.common.world.WorldEngine;

/** Frozen range. Root order is X, then Z, then Y; leaf order is X, Z, Y. */
public record VersionScope(
        int centerX, int centerZ, int radiusChunks, int minY, int maxY, int focusY) {
    public static final int MAX_ROOTS = 262144;

    public VersionScope(int x, int z, int radius, int minY, int maxY) {
        this(x, z, radius, minY, maxY, Math.max(minY, Math.min(maxY - 1, 64)));
    }

    public VersionScope {
        if (Math.abs((long) centerX) > 30_000_000
                || Math.abs((long) centerZ) > 30_000_000
                || radiusChunks < 1
                || radiusChunks > 4096
                || minY < -4096
                || maxY > 4096
                || minY >= maxY
                || focusY < minY
                || focusY >= maxY) throw new IllegalArgumentException("invalid version scope");
        long nx =
                ((long) centerX + radiusChunks * 16L >> 9)
                        - ((long) centerX - radiusChunks * 16L >> 9)
                        + 1;
        long nz =
                ((long) centerZ + radiusChunks * 16L >> 9)
                        - ((long) centerZ - radiusChunks * 16L >> 9)
                        + 1;
        if (nx * nz * ((maxY - 1 >> 9) - (minY >> 9) + 1) > MAX_ROOTS)
            throw new IllegalArgumentException("version scope too large");
    }

    public int minRootX() {
        return (centerX - radiusChunks * 16) >> 9;
    }

    public int minRootZ() {
        return (centerZ - radiusChunks * 16) >> 9;
    }

    public int width() {
        return ((centerX + radiusChunks * 16) >> 9) - minRootX() + 1;
    }

    public int depth() {
        return ((centerZ + radiusChunks * 16) >> 9) - minRootZ() + 1;
    }

    public int rootCount() {
        return width() * depth() * (((maxY - 1) >> 9) - (minY >> 9) + 1);
    }

    public long rootAt(int i) {
        if (i < 0 || i >= rootCount()) throw new IllegalArgumentException("root index");
        return WorldEngine.getWorldSectionId(
                4,
                minRootX() + i % width(),
                (minY >> 9) + i / (width() * depth()),
                minRootZ() + (i / width()) % depth());
    }

    public int rootIndex(long root) {
        if ((root & 15) != 0 || WorldEngine.getLevel(root) != 4) return -1;
        int x = WorldEngine.getX(root) - minRootX(),
                z = WorldEngine.getZ(root) - minRootZ(),
                y = WorldEngine.getY(root) - (minY >> 9);
        if (x < 0
                || x >= width()
                || z < 0
                || z >= depth()
                || y < 0
                || y >= (((maxY - 1) >> 9) - (minY >> 9) + 1)) return -1;
        return x + width() * (z + depth() * y);
    }

    public boolean contains(long key) {
        if ((key & 15) != 0 || WorldEngine.getLevel(key) != 0) return false;
        long x = (long) WorldEngine.getX(key) * 32,
                z = (long) WorldEngine.getZ(key) * 32,
                y = (long) WorldEngine.getY(key) * 32;
        return x <= centerX + radiusChunks * 16L
                && x + 32 > centerX - radiusChunks * 16L
                && z <= centerZ + radiusChunks * 16L
                && z + 32 > centerZ - radiusChunks * 16L
                && y < maxY
                && y + 32 > minY;
    }

    /** Delivery order is independent of digest order: visible-height nearby nodes arrive first. */
    public int compareDelivery(long a, long b) {
        int order = Long.compare(deliveryDistance(a), deliveryDistance(b));
        return order != 0 ? order : Long.compare(a, b);
    }

    private long deliveryDistance(long key) {
        long dx = distance(centerX, (long) WorldEngine.getX(key) * 32),
                dz = distance(centerZ, (long) WorldEngine.getZ(key) * 32),
                dy = distance(focusY, (long) WorldEngine.getY(key) * 32);
        return dx * dx + dz * dz + 4 * dy * dy;
    }

    private static long distance(long focus, long start) {
        return focus < start ? start - focus : focus > start + 32 ? focus - start - 32 : 0;
    }

    public static long root(long key) {
        return WorldEngine.getWorldSectionId(
                4,
                WorldEngine.getX(key) >> 4,
                WorldEngine.getY(key) >> 4,
                WorldEngine.getZ(key) >> 4);
    }

    /** Equal clipped masks share a cached digest, including between different players. */
    public int mask(long root) {
        int x = WorldEngine.getX(root) << 4,
                y = WorldEngine.getY(root) << 4,
                z = WorldEngine.getZ(root) << 4;
        return clamp(((centerX - radiusChunks * 16) >> 5) - x)
                | (clamp(((centerX + radiusChunks * 16) >> 5) - x) << 4)
                | (clamp(((centerZ - radiusChunks * 16) >> 5) - z) << 8)
                | (clamp(((centerZ + radiusChunks * 16) >> 5) - z) << 12)
                | (clamp((minY >> 5) - y) << 16)
                | (clamp(((maxY - 1) >> 5) - y) << 20);
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(15, v));
    }

    public static int slot(long key) {
        return (WorldEngine.getX(key) & 15)
                | ((WorldEngine.getZ(key) & 15) << 4)
                | ((WorldEngine.getY(key) & 15) << 8);
    }

    public static long node(long root, int slot) {
        if (slot < 0 || slot >= 4096) throw new IllegalArgumentException("leaf slot");
        return WorldEngine.getWorldSectionId(
                0,
                (WorldEngine.getX(root) << 4) + (slot & 15),
                (WorldEngine.getY(root) << 4) + (slot >> 8),
                (WorldEngine.getZ(root) << 4) + ((slot >> 4) & 15));
    }
}
