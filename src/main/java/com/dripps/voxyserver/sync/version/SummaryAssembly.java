package com.dripps.voxyserver.sync.version;

import java.util.*;

/** Out-of-order, idempotent pages; nothing can consume an incomplete inventory. */
public final class SummaryAssembly {
    public static final int PAGE_SIZE = 256;
    private final long[] digests;
    private final BitSet received = new BitSet();
    private final int pages;

    public SummaryAssembly(VersionScope scope) {
        digests = new long[scope.rootCount()];
        pages = (digests.length + PAGE_SIZE - 1) / PAGE_SIZE;
    }

    public synchronized boolean add(int page, int count, long[] values) {
        if (count != pages
                || page < 0
                || page >= pages
                || values.length != Math.min(PAGE_SIZE, digests.length - page * PAGE_SIZE))
            throw new IllegalArgumentException("summary page bounds");
        int offset = page * PAGE_SIZE;
        if (received.get(page)) {
            for (int i = 0; i < values.length; i++)
                if (digests[offset + i] != values[i])
                    throw new IllegalArgumentException("conflicting duplicate summary");
            return complete();
        }
        System.arraycopy(values, 0, digests, offset, values.length);
        received.set(page);
        return complete();
    }

    public synchronized boolean complete() {
        return received.cardinality() == pages;
    }

    public synchronized long digest(int index) {
        if (!complete()) throw new IllegalStateException("incomplete summary");
        return digests[index];
    }
}
