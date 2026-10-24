package com.dripps.voxyserver.sync;

import net.minecraft.world.level.chunk.DataLayer;

import java.util.Map;
import java.util.function.IntBinaryOperator;
import java.util.function.IntFunction;

/** Helpers for reconstructing effective sky light when Minecraft omits a uniform section layer. */
public final class SkyLightSnapshot {
    private SkyLightSnapshot() {}

    /**
     * In Minecraft's sky light storage, a missing layer inherits the first stored layer above it.
     * The y coordinate within the layer is preserved during that lookup. If no stored layer exists
     * above, the sky light default is full skylight (15).
     */
    public static DataLayer inheritAbove(
            int sectionY, int maxSectionY, IntFunction<DataLayer> layerAt) {
        DataLayer inherited = null;
        for (int y = sectionY + 1; y <= maxSectionY; y++) {
            inherited = layerAt.apply(y);
            if (inherited != null) break;
        }
        if (inherited == null) return new DataLayer(15);
        return inherited.copy();
    }

    public static DataLayer inheritAbove(
            int sectionY, int maxSectionY, Map<Integer, DataLayer> layers) {
        return inheritAbove(sectionY, maxSectionY, layers::get);
    }

    /** Retained for callers which only have column samples. */
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
