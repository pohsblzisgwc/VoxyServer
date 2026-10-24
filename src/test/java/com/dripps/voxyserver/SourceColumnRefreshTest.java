package com.dripps.voxyserver;

import com.dripps.voxyserver.sync.*;

import java.util.concurrent.TimeUnit;

public final class SourceColumnRefreshTest {
    public static void runAllTests() {
        var audits = new SourceColumnRefresh<String>();
        var queue = new DirtyRegionQueue<String>(0, 0, 0);
        long period = TimeUnit.SECONDS.toNanos(180);
        for (int pass = 0; pass < 32; pass++) {
            for (int x = 0; x < 32; x++)
                for (int z = 0; z < 32; z++)
                    if (audits.admit("world", x, z, pass * 1_000_000_000L, period))
                        for (int y = -4; y < 20; y++)
                            queue.mark("world", x << 4, y << 4, z << 4, 0);
            if (pass == 0) {
                require(queue.dirtyCells() == 24576, "initial source work lost");
                int processed = 0;
                DirtyRegionQueue.Work<String> work;
                while ((work = queue.poll(0)) != null)
                    processed += Integer.bitCount(work.sourceMask());
                require(
                        processed == 24576 && queue.dirtyCells() == 0,
                        "initial scan failed to drain");
            } else
                require(queue.dirtyCells() == 0, "load/unload re-enqueued unchanged whole columns");
        }
        require(
                audits.scheduled() == 1024 && audits.skipped() == 31744,
                "lifecycle audits were not coalesced");
        // Real block/light/biome dirtiness bypasses lifecycle rate limiting.
        for (int event = 0; event < 100000; event++) queue.mark("world", 0, 64, 0, 1);
        require(queue.dirtyCells() == 1, "redstone events multiplied pending work");
        var changed = queue.poll(1);
        require(
                changed != null && Integer.bitCount(changed.sourceMask()) == 1,
                "dirty source blocked by lifecycle cooldown");
        require(!audits.admit("world", 0, 0, period - 1, period), "early audit admitted");
        require(audits.admit("world", 0, 0, period, period), "low-frequency audit never resumed");
        require(audits.admit("other-dimension", 0, 0, 1, period), "dimensions conflated");
        require(audits.admit("world", 0, 0, period, 0), "zero interval not honored");
        audits.clear();
        require(audits.admit("world", 0, 0, 0, period), "restart retained stale admission");
        System.out.println(
                "PASS SourceColumnRefreshTest: 32 x 1024 lifecycle audits, initial 24576 sections"
                    + " drain once, 96.875% repeat admissions suppressed, 100000 dirty events"
                    + " coalesce and bypass audit delay; dimensions/deadlines/reset verified");
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
