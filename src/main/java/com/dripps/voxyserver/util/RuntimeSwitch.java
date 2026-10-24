package com.dripps.voxyserver.util;

import java.util.Objects;
import java.util.function.Supplier;

/** Owns one runtime; detach before draining so permanent event bridges cannot admit more work. */
public final class RuntimeSwitch<T extends AutoCloseable> {
    private volatile T current;
    private boolean failedStop;

    public T get() {
        return current;
    }

    public synchronized void start(Supplier<T> factory) {
        if (failedStop)
            throw new IllegalStateException(
                    "Previous runtime failed to stop; restart the server before enabling");
        if (current == null) current = Objects.requireNonNull(factory.get());
    }

    public synchronized void stop() {
        T previous = current;
        current = null;
        if (previous == null) return;
        try {
            previous.close();
        } catch (Throwable error) {
            failedStop = true;
            throw new IllegalStateException(
                    "Runtime shutdown failed; restart the server before enabling", error);
        }
    }
}
