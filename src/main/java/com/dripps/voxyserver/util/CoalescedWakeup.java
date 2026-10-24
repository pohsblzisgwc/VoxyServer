package com.dripps.voxyserver.util;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Completion callbacks enqueue one maintenance turn; they never perform maintenance themselves. */
public final class CoalescedWakeup {
    private final Executor executor;
    private final Runnable work;
    private final BooleanSupplier stopped;
    private final AtomicBoolean queued = new AtomicBoolean();

    public CoalescedWakeup(Executor executor, Runnable work, BooleanSupplier stopped) {
        this.executor = executor;
        this.work = work;
        this.stopped = stopped;
    }

    public void request() {
        if (stopped.getAsBoolean() || !queued.compareAndSet(false, true)) return;
        try {
            executor.execute(
                    () -> {
                        queued.set(false);
                        if (!stopped.getAsBoolean()) work.run();
                    });
        } catch (RejectedExecutionException closed) {
            queued.set(false);
        }
    }
}
