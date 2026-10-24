package com.dripps.voxyserver.server.background;

import java.util.random.RandomGenerator;

/** Constant-work annulus sampling; never constructs or walks a view-sized coordinate list. */
public final class BackgroundGenerationArea {
    public record Point(int x, int z) {}

    private BackgroundGenerationArea() {}

    public static Point sample(
            int centerX, int centerZ, int minimum, int maximum, RandomGenerator random) {
        if (minimum < 0 || maximum <= minimum) return null;
        double radius =
                Math.sqrt(
                        (double) minimum * minimum
                                + random.nextDouble()
                                        * ((double) maximum * maximum
                                                - (double) minimum * minimum));
        double angle = random.nextDouble() * Math.PI * 2;
        long x = (long) centerX + Math.round(Math.cos(angle) * radius),
                z = (long) centerZ + Math.round(Math.sin(angle) * radius);
        if (x < Integer.MIN_VALUE
                || x > Integer.MAX_VALUE
                || z < Integer.MIN_VALUE
                || z > Integer.MAX_VALUE) return null;
        var point = new Point((int) x, (int) z);
        return isWorldChunk(point) && contains(point, centerX, centerZ, minimum, maximum)
                ? point
                : null;
    }

    /** Prevent block-coordinate shifts from wrapping candidates back inside the world border. */
    public static boolean isWorldChunk(Point point) {
        int limit = 30_000_000 / 16;
        return point.x >= -limit && point.x < limit && point.z >= -limit && point.z < limit;
    }

    public static boolean contains(Point p, int centerX, int centerZ, int minimum, int maximum) {
        if (minimum < 0 || maximum <= minimum) return false;
        long x = (long) p.x - centerX, z = (long) p.z - centerZ;
        if (Math.abs(x) > maximum || Math.abs(z) > maximum) return false;
        long distance = x * x + z * z;
        return distance >= (long) minimum * minimum && distance <= (long) maximum * maximum;
    }
}
