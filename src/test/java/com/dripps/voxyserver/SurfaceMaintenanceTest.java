package com.dripps.voxyserver;

import com.dripps.voxyserver.sync.*;

import me.cortex.voxy.common.world.WorldEngine;

import java.util.*;

public final class SurfaceMaintenanceTest {
    public static void runAllTests() {
        for (int center = 0; center < 16; center++) {
            var seen = new BitSet();
            for (int rank = 0; rank < 16; rank++) seen.set(SourceSectionOrder.pairAt(center, rank));
            require(
                    seen.cardinality() == 16 && SourceSectionOrder.pairAt(center, 0) == center,
                    "surface ordering lost/deprioritized a layer");
        }
        var q = new DirtyRegionQueue<String>(0, 0, 0);
        for (int y = -4; y < 20; y++) q.mark("world", 0, y << 4, 0, 0);
        var first = q.poll(0, (dim, root) -> 63);
        require(
                first != null && WorldEngine.getY(first.node()) == 1,
                "underground work won before sea/surface");
        int total = Integer.bitCount(first.sourceMask());
        for (int i = 0; i < 30 && q.dirtyCells() > 0; i++) {
            var w = q.poll(0, (dim, root) -> 63);
            require(w != null, "surface priority stranded deep work");
            total += Integer.bitCount(w.sourceMask());
        }
        require(
                total == 24 && q.dirtyCells() == 0,
                "surface order dropped or duplicated source sections");
        var fair = new DirtyRegionQueue<String>(0, 0, 0);
        fair.mark("world", 0, -64, 0, 0);
        for (int i = 0; i < 4; i++) {
            fair.mark("world", i * 32, 48, 0, 0);
            var w = fair.poll(0, (dim, root) -> 63);
            require(w != null && WorldEngine.getY(w.node()) == 1, "surface share not honored");
        }
        fair.mark("world", 128, 48, 0, 0);
        var deep = fair.poll(0, (dim, root) -> 63);
        require(
                deep != null && WorldEngine.getY(deep.node()) == -2,
                "deep work starved beyond fifth turn");
        var retries = new DirtyRegionQueue<String>(0, 0, 0);
        retries.mark("world", 0, 48, 0, 0);
        var work = retries.poll(0, (d, r) -> 63);
        retries.retry(work, 0);
        require(
                retries.poll(999_999_999L, (d, r) -> 63) == null,
                "priority bypassed retry backoff");
        require(
                retries.poll(1_000_000_000L, (d, r) -> 63) != null,
                "priority retry never recovered");
        var noSurface = new DirtyRegionQueue<String>(0, 0, 0);
        noSurface.mark("world", 0, -64, 0, 0);
        require(noSurface.poll(0, (d, r) -> 63) != null, "surface absence stopped ordinary work");
        // Two players' loaded regions and changing height preferences must still drain each source
        // once.
        var load = new DirtyRegionQueue<String>(0, 0, 0);
        for (int x = 0; x < 32; x++)
            for (int z = 0; z < 32; z++)
                for (int y = -4; y < 20; y++) load.mark("world", x * 16, y * 16, z * 16, 0);
        int processed = 0;
        long started = System.nanoTime();
        while (load.dirtyCells() > 0) {
            var w = load.poll(0, (d, r) -> 63);
            require(w != null, "load stalled");
            processed += Integer.bitCount(w.sourceMask());
        }
        require(processed == 24576, "load lost sources");
        System.out.printf(
                "PASS SurfaceMaintenanceTest: surface-first/deep fairness, retry, all 24576 loaded"
                    + " sources preserved; %.2f ms queue-only, no world conversion/TPS claim%n",
                (System.nanoTime() - started) / 1e6);
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
