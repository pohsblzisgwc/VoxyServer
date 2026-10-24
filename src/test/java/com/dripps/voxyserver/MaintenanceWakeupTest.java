package com.dripps.voxyserver;

import com.dripps.voxyserver.util.CoalescedWakeup;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class MaintenanceWakeupTest {
    public static void runAllTests() throws Exception {
        var tasks = new java.util.ArrayDeque<Runnable>();
        var stopped = new AtomicBoolean();
        var completed = new AtomicInteger();
        Thread main = Thread.currentThread();
        var wake =
                new CoalescedWakeup(
                        tasks::add,
                        () -> {
                            if (Thread.currentThread() == main)
                                throw new AssertionError("maintenance ran in completion caller");
                            completed.incrementAndGet();
                        },
                        stopped::get);
        var futures = new CompletableFuture<?>[1000];
        for (int i = 0; i < 1000; i++) {
            var future = new CompletableFuture<Void>();
            future.whenComplete((unused, error) -> wake.request());
            futures[i] = future;
            future.complete(null);
        }
        require(
                tasks.size() == 1 && completed.get() == 0,
                "completions flooded queue or performed maintenance inline");
        var thread = new Thread(tasks.remove());
        thread.start();
        thread.join();
        require(completed.get() == 1, "maintenance owner did not receive completion wake");
        wake.request();
        require(tasks.size() == 1, "later completion wake lost");
        stopped.set(true);
        var closed = new Thread(tasks.remove());
        closed.start();
        closed.join();
        require(completed.get() == 1, "queued completion ran after shutdown");
        wake.request();
        require(tasks.isEmpty(), "shutdown admitted another wake");
        var rejected = new AtomicInteger();
        var rejectedWake =
                new CoalescedWakeup(
                        r -> {
                            rejected.incrementAndGet();
                            throw new RejectedExecutionException();
                        },
                        () -> {
                            throw new AssertionError();
                        },
                        () -> false);
        rejectedWake.request();
        rejectedWake.request();
        require(rejected.get() == 2, "executor rejection retained queued flag");
        System.out.println(
                "PASS MaintenanceWakeupTest: 1000 async completions coalesce, no main-thread"
                    + " maintenance, later wake retained, shutdown/rejected executor safe");
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
