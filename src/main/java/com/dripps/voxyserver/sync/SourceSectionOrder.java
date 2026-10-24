package com.dripps.voxyserver.sync;

/** Sixteen 32-block layers per bitmap root, ordered around the observed surface. */
public final class SourceSectionOrder {
    private static final int[][] ORDER = new int[16][16];

    static {
        for (int center = 0; center < 16; center++) {
            int index = 0;
            ORDER[center][index++] = center;
            for (int distance = 1; distance < 16; distance++) {
                if (center + distance < 16) ORDER[center][index++] = center + distance;
                if (center - distance >= 0) ORDER[center][index++] = center - distance;
            }
        }
    }

    private SourceSectionOrder() {}

    public static int pairAt(int preferred, int rank) {
        return ORDER[Math.clamp(preferred, 0, 15)][rank];
    }
}
