package com.dripps.voxyserver.server;

/** Dimension-tagged keys used only by the retired Manifest regression fixtures. */
final class LegacySectionKeys {
    static long composeSectionKey(int dimension, long key) {
        return key | ((long) (dimension & 15) << 60);
    }

    static long extractSectionKey(long key) {
        return key & 0x0fffffffffffffffL;
    }

    static int extractSectionDimOrdinal(long key) {
        return (int) (key >>> 60) & 15;
    }
}
