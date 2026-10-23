package com.dripps.voxyserver.sync;

import me.cortex.voxy.common.world.WorldEngine;

public final class NativeLodUnits {
    private NativeLodUnits() {}

    /** Voxy stores render distance in top-level (512 block) units, not L0 (32 block) units. */
    public static int radiusChunks(float distance) {
        if (!Float.isFinite(distance) || distance <= 0)
            throw new IllegalArgumentException("invalid native render distance");
        return (int) Math.min(4096, Math.ceil(distance * (32L << WorldEngine.MAX_LOD_LAYER) / 16));
    }
}
