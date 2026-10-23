package com.dripps.voxyserver.sync;

import me.cortex.voxy.common.world.WorldEngine;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 16-cube source bits. Marking never waits for a full queue scan, journal write, or hashing. */
public final class DirtyRegionQueue<D> {
    public record Work<D>(D dimension, long node, int sourceMask) {}

    private record Root<D>(D dimension, long key) {}

    private static final class Bits {
        final BitSet dirty = new BitSet();
        volatile long first, last, nextPoll;
        long version;
        boolean enqueued;
    }

    private record Entry<D>(Root<D> root, Bits bits) {}

    private final ConcurrentHashMap<Root<D>, Bits> roots = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Entry<D>> incoming = new ConcurrentLinkedQueue<>();
    private final ArrayDeque<Entry<D>> turns = new ArrayDeque<>(); // maintenance worker only
    private final AtomicInteger count = new AtomicInteger();
    private final com.dripps.voxyserver.util.BoundedLruCache<Work<D>, Long> cooldowns =
            new com.dripps.voxyserver.util.BoundedLruCache<>(65_536);
    private volatile long quietNanos, cooldownNanos;
    private final long maxWaitNanos;

    public DirtyRegionQueue(long quietNanos, long maxWaitNanos, long cooldownNanos) {
        if (quietNanos < 0 || maxWaitNanos < quietNanos || cooldownNanos < 0)
            throw new IllegalArgumentException("invalid dirty timing");
        this.quietNanos = quietNanos;
        this.maxWaitNanos = maxWaitNanos;
        this.cooldownNanos = cooldownNanos;
    }

    public void updateTiming(long quiet, long cooldown) {
        if (quiet < 0 || quiet > maxWaitNanos || cooldown < 0)
            throw new IllegalArgumentException("invalid dirty timing");
        quietNanos = quiet;
        cooldownNanos = cooldown;
    }

    public void mark(D dimension, int blockX, int blockY, int blockZ, long now) {
        int x = blockX >> 4,
                y = blockY >> 4,
                z = blockZ >> 4,
                index = (x & 31) | ((z & 31) << 5) | ((y & 31) << 10);
        Root<D> root =
                new Root<>(dimension, WorldEngine.getWorldSectionId(4, x >> 5, y >> 5, z >> 5));
        while (true) {
            Bits bits = roots.computeIfAbsent(root, key -> new Bits());
            synchronized (bits) {
                if (roots.get(root) != bits) continue;
                if (bits.dirty.isEmpty()) bits.first = now;
                if (!bits.dirty.get(index)) {
                    bits.nextPoll = 0;
                    bits.version++;
                    bits.dirty.set(index);
                    count.incrementAndGet();
                }
                bits.last = now;
                if (!bits.enqueued) {
                    bits.enqueued = true;
                    incoming.add(new Entry<>(root, bits));
                }
                return;
            }
        }
    }

    public Work<D> poll(long now) {
        for (int i = 0; i < 128; i++) {
            var e = incoming.poll();
            if (e == null) break;
            turns.addLast(e);
        }
        int attempts = Math.min(turns.size(), 128);
        for (int turn = 0; turn < attempts; turn++) {
            var entry = turns.removeFirst();
            var root = entry.root;
            var b = entry.bits;
            if (roots.get(root) != b) continue;
            BitSet pending;
            long version;
            synchronized (b) {
                if (b.dirty.isEmpty() && now - b.last >= cooldownNanos) {
                    roots.remove(root, b);
                    continue;
                }
                turns.addLast(entry);
                if (now < b.nextPoll || now < Math.min(b.last + quietNanos, b.first + maxWaitNanos))
                    continue;
                pending = (BitSet) b.dirty.clone();
                version = b.version; // <=4 KiB, no scan under the source writer's monitor.
            }
            long next = Long.MAX_VALUE;
            for (int i = pending.nextSetBit(0); i >= 0; i = pending.nextSetBit(i + 1)) {
                int x = (i & 31) >> 1, y = (i >> 10) >> 1, z = ((i >> 5) & 31) >> 1;
                long r = root.key,
                        node =
                                WorldEngine.getWorldSectionId(
                                        0,
                                        (WorldEngine.getX(r) << 4) + x,
                                        (WorldEngine.getY(r) << 4) + y,
                                        (WorldEngine.getZ(r) << 4) + z);
                Work<D> cooldownKey = new Work<>(root.dimension, node, 0);
                Long eligible = cooldowns.get(cooldownKey);
                if (eligible != null && now < eligible) {
                    next = Math.min(next, eligible);
                    continue;
                }
                int mask = 0;
                synchronized (b) {
                    for (int slot = 0; slot < 8; slot++) {
                        int source =
                                (x * 2 + (slot & 1))
                                        | ((z * 2 + ((slot >> 1) & 1)) << 5)
                                        | ((y * 2 + (slot >> 2)) << 10);
                        if (b.dirty.get(source)) {
                            mask |= 1 << slot;
                            b.dirty.clear(source);
                        }
                    }
                    count.addAndGet(-Integer.bitCount(mask));
                }
                if (mask == 0) continue;
                cooldowns.put(cooldownKey, now + cooldownNanos);
                return new Work<>(root.dimension, node, mask);
            }
            synchronized (b) {
                if (b.version == version) b.nextPoll = next;
            }
        }
        return null;
    }

    public void retry(Work<D> work, long now) {
        long key = work.node;
        for (int slot = 0; slot < 8; slot++)
            if ((work.sourceMask & (1 << slot)) != 0)
                mark(
                        work.dimension,
                        (WorldEngine.getX(key) << 5) + ((slot & 1) << 4),
                        (WorldEngine.getY(key) << 5) + ((slot >> 2) << 4),
                        (WorldEngine.getZ(key) << 5) + (((slot >> 1) & 1) << 4),
                        now);
        cooldowns.put(new Work<>(work.dimension, key, 0), now + 1_000_000_000L);
    }

    public record SavedRoot<D>(D dimension, long key, long[] bits) {}

    public List<SavedRoot<D>> snapshot() {
        var out = new ArrayList<SavedRoot<D>>();
        roots.forEach(
                (root, bits) -> {
                    synchronized (bits) {
                        if (!bits.dirty.isEmpty())
                            out.add(
                                    new SavedRoot<>(
                                            root.dimension, root.key, bits.dirty.toLongArray()));
                    }
                });
        return out;
    }

    public List<SavedRoot<D>> snapshotIncluding(Work<D> active) {
        var copy = new DirtyRegionQueue<D>(quietNanos, maxWaitNanos, cooldownNanos);
        for (var saved : snapshot()) copy.restore(saved.dimension, saved.key, saved.bits, 0);
        if (active != null) copy.retry(active, 0);
        return copy.snapshot();
    }

    public void restore(D dimension, long root, long[] words, long now) {
        if (words.length > 512 || !DemandGroup.valid(root) || WorldEngine.getLevel(root) != 4)
            throw new IllegalArgumentException("invalid dirty bitmap");
        Root<D> key = new Root<>(dimension, root);
        while (true) {
            Bits b = roots.computeIfAbsent(key, k -> new Bits());
            synchronized (b) {
                if (roots.get(key) != b) continue;
                if (b.dirty.isEmpty()) {
                    b.first = now;
                    b.last = now - quietNanos;
                }
                int before = b.dirty.cardinality();
                b.dirty.or(BitSet.valueOf(words));
                count.addAndGet(b.dirty.cardinality() - before);
                b.nextPoll = 0;
                b.version++;
                if (!b.enqueued) {
                    b.enqueued = true;
                    incoming.add(new Entry<>(key, b));
                }
                return;
            }
        }
    }

    public int roots() {
        return roots.size();
    }

    public int dirtyCells() {
        return count.get();
    }
}
