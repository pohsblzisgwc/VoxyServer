package com.dripps.voxyserver.sync.version;

import com.dripps.voxyserver.network.VersionTablePayload;

import it.unimi.dsi.fastutil.longs.*;

import java.util.*;

/** Stages complete root tables; never publishes partial claims or clears terrain. */
public final class VersionTables {
    public static final int MAX_ENTRIES = 16_777_216;

    private static final class RootPages {
        final long digest;
        final VersionTablePayload[] pages;

        RootPages(VersionTablePayload p) {
            digest = p.digest();
            pages = new VersionTablePayload[p.pages()];
        }
    }

    private final VersionScope scope;
    private final Map<Long, RootPages> roots = new HashMap<>();
    private int entries;

    public VersionTables(VersionScope scope) {
        this.scope = scope;
    }

    public synchronized void add(VersionTablePayload p) {
        if (scope.rootIndex(p.root()) < 0)
            throw new IllegalArgumentException("table outside range");
        RootPages root = roots.computeIfAbsent(p.root(), k -> new RootPages(p));
        if (root.digest != p.digest() || root.pages.length != p.pages())
            throw new IllegalArgumentException("conflicting root snapshot");
        var previous = root.pages[p.page()];
        if (previous != null) {
            if (!Arrays.equals(previous.keys(), p.keys())
                    || !Arrays.equals(previous.versions(), p.versions()))
                throw new IllegalArgumentException("conflicting duplicate table");
            return;
        }
        for (long key : p.keys())
            if (!scope.contains(key))
                throw new IllegalArgumentException("table node outside range");
        if ((entries += p.keys().length) > MAX_ENTRIES)
            throw new IllegalArgumentException("version table memory limit");
        root.pages[p.page()] = p;
    }

    public synchronized Long2IntOpenHashMap complete(int expectedRoots) {
        if (expectedRoots != roots.size()) throw new IllegalStateException("missing root tables");
        var out = new Long2IntOpenHashMap();
        for (var entry : roots.entrySet()) {
            int[] values = new int[4096];
            int previous = -1;
            for (var page : entry.getValue().pages) {
                if (page == null) throw new IllegalStateException("incomplete root table");
                for (int i = 0; i < page.keys().length; i++) {
                    long key = page.keys()[i];
                    int slot = VersionScope.slot(key);
                    if (slot <= previous)
                        throw new IllegalArgumentException("duplicate or unordered leaf");
                    previous = slot;
                    values[slot] = page.versions()[i];
                    out.put(key, page.versions()[i]);
                }
            }
            if (VersionDigest.compute(values, null, entry.getKey()) != entry.getValue().digest)
                throw new IllegalArgumentException("version sequence digest mismatch");
        }
        return out;
    }

    public synchronized Set<Long> changedRoots() {
        return Set.copyOf(roots.keySet());
    }
}
