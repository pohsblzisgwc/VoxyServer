package com.dripps.voxyserver.network;

import me.cortex.voxy.common.world.WorldEngine;

/** Frozen horizontal level-zero inventory bounds, in 32-block sections. */
public record ManifestScope(int centerX, int centerZ, int radius) {
    public ManifestScope {
        if (radius < 0 || radius > 2048) throw new IllegalArgumentException("manifest radius must be 0..2048");
    }
    public boolean contains(long key) {
        return WorldEngine.getLevel(key) == 0
                && Math.abs((long) WorldEngine.getX(key) - centerX) <= radius
                && Math.abs((long) WorldEngine.getZ(key) - centerZ) <= radius;
    }
}
