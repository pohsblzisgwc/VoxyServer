package com.dripps.voxyserver.sync;

import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.other.Mapper;

/** Overwrites one native node; it never deletes independently acquired descendants. */
public final class NativeNodeInstaller {
    private NativeNodeInstaller() {}

    public static void install(
            WorldEngine world, long key, long[] palette, short[] indices, byte children) {
        if (!DemandGroup.valid(key) || indices.length != 32 * 32 * 32 || palette.length == 0)
            throw new IllegalArgumentException("invalid native node");
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
            } finally {
                target.release();
            }
        }
    }
}
