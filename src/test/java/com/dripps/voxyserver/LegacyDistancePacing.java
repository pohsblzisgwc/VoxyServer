package com.dripps.voxyserver;

/** Retired distance-pacing regression fixture; not in the runtime jar. */
final class LegacyDistancePacing {
    public static boolean checkDistancePacing(
            int distSections, int sequenceIndex, boolean enablePacing) {
        if (!enablePacing) return true;
        if (distSections <= 16) {
            return true;
        }
        if (distSections <= 48) {
            return (sequenceIndex & 1) == 0;
        }
        return (sequenceIndex & 3) == 0;
    }
}
