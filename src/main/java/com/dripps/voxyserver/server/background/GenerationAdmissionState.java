package com.dripps.voxyserver.server.background;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Unfinished shared native work survives runtime disposal; completion releases debt without
 * polling.
 */
public final class GenerationAdmissionState {
    private final GenerationRateBudget rate = new GenerationRateBudget();

    public GenerationRateBudget rateBudget() {
        return rate;
    }

    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();

    public void retain(CompletableFuture<?> future) {
        if (future.isDone()) return;
        if (pending.add(future)) future.whenComplete((unused, error) -> pending.remove(future));
    }

    public int size() {
        return pending.size();
    }
}
