package com.dripps.voxyserver.sync;

import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.common.world.other.Mipper;

/** Overwrites one native node; it never deletes independently acquired descendants. */
public final class NativeNodeInstaller {
    private NativeNodeInstaller() {}

    public static void install(
            WorldEngine world, long key, long[] palette, short[] indices, byte children) {
        if (((key & 15) != 0 || WorldEngine.getLevel(key) > 4)
                || indices.length != 32 * 32 * 32
                || palette.length == 0) throw new IllegalArgumentException("invalid native node");
        // Validate before acquiring or changing storage, including the final voxel.
        for (short index : indices)
            if ((index & 65535) >= palette.length)
                throw new IllegalArgumentException("invalid palette index");
        synchronized (world) {
            var target = world.acquire(key);
            try {
                long[] values = target._unsafeGetRawDataArray();
                int nonAir = 0;
                for (int i = 0; i < values.length; i++) {
                    long value = palette[indices[i] & 65535];
                    values[i] = value;
                    if (!Mapper.isAir(value)) nonAir++;
                }
                if (target.lvl == 0) {
                    target.addNonEmptyBlockCount(nonAir - target.getNonEmptyBlockCount());
                    target.updateLvl0State();
                } else
                    target._unsafeSetNonEmptyChildren(
                            (byte) (target.getNonEmptyChildren() | children));
                world.markDirty(target, WorldEngine.DEFAULT_UPDATE_FLAGS, 63);
                propagateParents(world, target);
            } finally {
                target.release();
            }
        }
    }

    /** Native WorldUpdater's parent operation, applied to an entire received 32-cube node. */
    private static void propagateParents(WorldEngine world, WorldSection start) {
        var child = start;
        boolean releaseChild = false;
        try {
            // An unchanged intermediate level may still have a missing/stale higher parent.
            for (int level = start.lvl + 1; level <= WorldEngine.MAX_LOD_LAYER; level++) {
                var parent = world.acquire(level, child.x >> 1, child.y >> 1, child.z >> 1);
                boolean keep = false;
                try {
                    int ox = (child.x & 1) << 4, oy = (child.y & 1) << 4, oz = (child.z & 1) << 4;
                    long[] from = child._unsafeGetRawDataArray(),
                            to = parent._unsafeGetRawDataArray();
                    boolean changed = false;
                    for (int y = 0; y < 16; y++)
                        for (int z = 0; z < 16; z++)
                            for (int x = 0; x < 16; x++) {
                                int source = (x << 1) | ((z << 1) << 5) | ((y << 1) << 10);
                                // Argument order is the original Voxy
                                // Mipper/WorldVoxilizedSectionMipper order.
                                long value =
                                        Mipper.mip(
                                                from[source],
                                                from[source + 1],
                                                from[source + 32],
                                                from[source + 33],
                                                from[source + 1024],
                                                from[source + 1025],
                                                from[source + 1056],
                                                from[source + 1057],
                                                world.getMapper());
                                int dest = (x + ox) | ((z + oz) << 5) | ((y + oy) << 10);
                                changed |= to[dest] != value;
                                to[dest] = value;
                            }
                    int childChange = parent.updateEmptyChildState(child);
                    if (changed || childChange != 0)
                        world.markDirty(
                                parent,
                                (changed ? WorldEngine.UPDATE_TYPE_BLOCK_BIT : 0)
                                        | (childChange != 0
                                                ? WorldEngine.UPDATE_TYPE_CHILD_EXISTENCE_BIT
                                                : 0),
                                changed ? 63 : 0);
                    // Keep the reference acquired until its values/topology have been propagated.
                    if (releaseChild) child.release();
                    child = parent;
                    releaseChild = true;
                    keep = true;
                } finally {
                    if (!keep) parent.release();
                }
            }
        } finally {
            if (releaseChild) child.release();
        }
    }
}
