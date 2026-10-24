package com.dripps.voxyserver;

import com.dripps.voxyserver.sync.*;

import me.cortex.voxy.common.world.WorldEngine;

public final class DirtyMaintenanceTest {
    private static final long SECOND = 1_000_000_000L;

    public static void runAllTests() {
        var q = new DirtyRegionQueue<String>(2 * SECOND, 30 * SECOND, 10 * SECOND);
        long start = System.nanoTime();
        for (int i = 0; i < 100_000; i++) q.mark("world", 0, 0, 0, i * 1000L);
        long marks = System.nanoTime() - start;
        require(q.roots() == 1 && q.dirtyCells() == 1, "redstone allocated work per event");
        require(q.poll(SECOND) == null, "quiet period ignored");
        var first = q.poll(3 * SECOND);
        require(
                first != null && first.node() == 0 && first.sourceMask() == 1,
                "dirty source copied unrelated sections");
        q.mark("world", 0, 0, 0, 3 * SECOND);
        require(q.poll(6 * SECOND) == null, "hot cell ignored cooldown");
        require(q.poll(13 * SECOND) != null, "cooldown work never resumed");
        for (int slot = 0; slot < 8; slot++)
            q.mark(
                    "world",
                    32 + ((slot & 1) << 4),
                    ((slot >> 2) << 4),
                    (((slot >> 1) & 1) << 4),
                    20 * SECOND);
        var all = q.poll(22 * SECOND);
        require(
                all != null && all.sourceMask() == 255 && WorldEngine.getX(all.node()) == 1,
                "eight dirty sources not coalesced");
        for (int i = 0; i < 40; i++) q.mark("world", 64, 0, 0, (30L + i) * SECOND);
        require(q.poll(69 * SECOND) != null, "continuous edits never reached maximum wait");
        var active = new DirtyRegionQueue<String>(2 * SECOND, 30 * SECOND, 10 * SECOND);
        active.mark("a", -16, -16, -16, 0);
        var negative = active.poll(2 * SECOND);
        require(
                negative != null
                        && WorldEngine.getX(negative.node()) == -1
                        && negative.sourceMask() == 128,
                "negative source identity");
        require(active.dirtyCells() == 0, "poll did not release dirty bits");
        var saved = active.snapshotIncluding(negative);
        require(active.dirtyCells() == 0 && saved.size() == 1, "checkpoint changed live queue");
        var resumed = new DirtyRegionQueue<String>(2 * SECOND, 30 * SECOND, 10 * SECOND);
        resumed.mark("a", -32, -32, -32, 2 * SECOND);
        for (var root : saved)
            resumed.restore(root.dimension(), root.key(), root.bits(), 2 * SECOND);
        var recovered = resumed.poll(4 * SECOND);
        require(
                recovered != null && recovered.sourceMask() == 129,
                "resume discarded current startup work");
        resumed.retry(recovered, 5 * SECOND);
        require(
                resumed.poll(6 * SECOND) == null && resumed.poll(7 * SECOND) != null,
                "failure recovery timing");
        // Earlier quiet roots must not starve a ready root beyond the bounded metadata rotation.
        var fair = new DirtyRegionQueue<String>(2 * SECOND, 30 * SECOND, 10 * SECOND);
        for (int i = 0; i < 200; i++) fair.mark("a", i * 512, 0, 0, 10 * SECOND);
        fair.mark("a", -512, 0, 0, 0);
        require(fair.poll(3 * SECOND) == null, "per-poll metadata bound ignored");
        var ready = fair.poll(3 * SECOND);
        if (ready == null) ready = fair.poll(3 * SECOND);
        require(ready != null, "ready dirty root starved behind quiet roots");
        for (int i = 0; i < 100; i++)
            require(active.poll(100 * SECOND) == null, "exhausted queue produced work");
        var slow = new DirtyRegionQueue<String>(2 * SECOND, 30 * SECOND, 10 * SECOND);
        slow.updateTiming(120 * SECOND, 0);
        slow.mark("a", 0, 0, 0, 0);
        require(
                slow.poll(30 * SECOND) == null && slow.poll(120 * SECOND) != null,
                "expanded quiet time silently capped");
        slow.updateTiming(0, 0);
        slow.mark("a", 0, 0, 0, 121 * SECOND);
        require(slow.poll(121 * SECOND) != null, "zero quiet/cooldown ignored");
        concurrentMarking();
        System.out.printf(
                "PASS DirtyMaintenanceTest: 100000 edits -> 1 source, %.2f ms marking;"
                        + " quiet/max-wait/cooldown/retry/journal/fairness%n",
                marks / 1e6);
    }

    private static void concurrentMarking() {
        var queue = new DirtyRegionQueue<String>(0, 0, 0);
        var done = new java.util.concurrent.atomic.AtomicBoolean();
        var failed = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var visited = new java.util.BitSet();
        Thread writer =
                new Thread(
                        () -> {
                            try {
                                for (int i = 0; i < 10000; i++)
                                    queue.mark("world", i * 16, 0, 0, i);
                            } catch (Throwable error) {
                                failed.set(error);
                            } finally {
                                done.set(true);
                            }
                        });
        Thread reader =
                new Thread(
                        () -> {
                            try {
                                long deadline = System.nanoTime() + 5_000_000_000L;
                                while (!done.get() || queue.dirtyCells() != 0) {
                                    if (System.nanoTime() > deadline)
                                        throw new AssertionError(
                                                "dirty queue lost its scheduling entry");
                                    var work = queue.poll(Long.MAX_VALUE / 2);
                                    if (work == null) {
                                        Thread.yield();
                                        continue;
                                    }
                                    for (int slot = 0; slot < 8; slot++)
                                        if ((work.sourceMask() & (1 << slot)) != 0)
                                            visited.set(
                                                    WorldEngine.getX(work.node()) * 2 + (slot & 1));
                                    require(
                                            queue.dirtyCells() >= 0,
                                            "concurrent dirty count became negative");
                                    queue.snapshot();
                                }
                            } catch (Throwable error) {
                                failed.set(error);
                            }
                        });
        writer.start();
        reader.start();
        try {
            writer.join(6000);
            reader.join(6000);
        } catch (InterruptedException error) {
            throw new AssertionError(error);
        }
        require(
                !writer.isAlive() && !reader.isAlive() && failed.get() == null,
                "mark/poll/checkpoint race: " + failed.get());
        require(
                visited.cardinality() == 10000 && queue.dirtyCells() == 0,
                "concurrent mark lost work");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
