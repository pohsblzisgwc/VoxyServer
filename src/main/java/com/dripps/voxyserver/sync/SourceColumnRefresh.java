package com.dripps.voxyserver.sync;

import com.dripps.voxyserver.util.BoundedLruCache;

/** Coalesces lifecycle audits only. Block/light/biome dirty events bypass this admission gate. */
public final class SourceColumnRefresh<D> {
    private record Column<D>(D dimension, int x, int z) {}

    private final BoundedLruCache<Column<D>, Long> admitted = new BoundedLruCache<>(65_536);
    private long scheduled, skipped;

    public synchronized boolean admit(D dimension, int x, int z, long now, long interval) {
        if (interval < 0) throw new IllegalArgumentException("negative audit interval");
        var key = new Column<>(dimension, x, z);
        Long previous = admitted.get(key);
        if (previous != null && now - previous < interval) {
            skipped++;
            return false;
        }
        admitted.put(key, now);
        scheduled++;
        return true;
    }

    public synchronized long scheduled() {
        return scheduled;
    }

    public synchronized long skipped() {
        return skipped;
    }

    public void clear() {
        admitted.clear();
    }
}
