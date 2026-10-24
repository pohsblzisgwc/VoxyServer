package com.dripps.voxyserver.sync.version;

import it.unimi.dsi.fastutil.longs.*;

import java.util.Set;
import java.util.function.LongToIntFunction;

/** Reconciles verification metadata only. Native terrain is deliberately outside this API. */
public final class VersionReconciliation {
    public record Plan(Long2IntOpenHashMap requested, LongOpenHashSet unconfirmed) {}

    private VersionReconciliation() {}

    public static Plan compare(
            VersionScope scope,
            Set<Long> changedRoots,
            Long2IntMap atStart,
            LongToIntFunction current,
            Long2IntMap server) {
        var wanted = new Long2IntOpenHashMap();
        var unconfirmed = new LongOpenHashSet();
        for (var e : atStart.long2IntEntrySet()) {
            long key = e.getLongKey();
            if (scope.contains(key)
                    && changedRoots.contains(VersionScope.root(key))
                    && !server.containsKey(key)) unconfirmed.add(key);
        }
        for (var e : server.long2IntEntrySet()) {
            long key = e.getLongKey();
            int version = e.getIntValue();
            if (!scope.contains(key)
                    || !changedRoots.contains(VersionScope.root(key))
                    || version < 1)
                throw new IllegalArgumentException("server table outside snapshot");
            int local = current.applyAsInt(key);
            if (local > version) unconfirmed.add(key);
            if (atStart.get(key) != version || local != version) wanted.put(key, version);
        }
        return new Plan(wanted, unconfirmed);
    }
}
