package com.dripps.voxyserver.sync;

import net.minecraft.world.level.chunk.DataLayer;

import java.util.function.IntBinaryOperator;

/** Missing skylight storage may inherit the first layer above; null does not itself mean zero. */
public final class SkyLightSnapshot {
    private SkyLightSnapshot() {}

    public static DataLayer inherited(IntBinaryOperator effectiveColumnLight) {
        int[] columns = new int[256];
        int first = -1;
        boolean uniform = true;
        for (int z = 0; z < 16; z++)
            for (int x = 0; x < 16; x++) {
                int value = effectiveColumnLight.applyAsInt(x, z);
                if (value < 0 || value > 15) throw new IllegalArgumentException("skylight range");
                columns[x | (z << 4)] = value;
                if (first == -1) first = value;
                else uniform &= first == value;
            }
        if (uniform) return new DataLayer(first);
        var layer = new DataLayer();
        for (int y = 0; y < 16; y++)
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++) layer.set(x, y, z, columns[x | (z << 4)]);
        return layer;
    }
}
