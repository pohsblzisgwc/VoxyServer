package com.dripps.voxyserver.server;

import com.dripps.voxyserver.network.LODSectionPayload;
import com.dripps.voxyserver.sync.*;
import com.dripps.voxyserver.util.BoundedLruCache;

import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.WorldIdentifier;

import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.biome.Biome;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Shared on-demand index. Invalidations and proof publication use the same short monitor. */
final class DemandIndex {
    record Key(Identifier dimension, long node) {}

    record Node(long revision, long hash, byte children, boolean present) {}

    record Probe(Key key, WorldIdentifier worldId, Registry<Biome> biomes) {}

    record CachedPayload(long revision, LODSectionPayload payload) {}

    record Proof(long coverage, long revision, long[] hashes) {}

    private final BoundedLruCache<Key, Node> nodes = new BoundedLruCache<>(65_536);
    private final BoundedLruCache<Key, Long> versions = new BoundedLruCache<>(65_536);
    private final BoundedLruCache<Key, CachedPayload> payloads = new BoundedLruCache<>(128);
    private final ConcurrentHashMap<Key, Probe> queued = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Key> order = new ConcurrentLinkedQueue<>();
    private final AtomicLong serial = new AtomicLong(1);
    long computed, cacheHits, absent, rejected, computeNanos, hashesComputed, hashNanos;

    synchronized void invalidate(Identifier dimension, long key) {
        Key k = new Key(dimension, key);
        // Cold edits must not populate or churn an index that nobody requested.
        if (versions.get(k) == null && nodes.get(k) == null && !queued.containsKey(k)) return;
        versions.put(k, serial.incrementAndGet());
        nodes.remove(k);
        payloads.remove(k);
    }

    private long revision(Key k) {
        return versions.compute(k, (key, old) -> old == null ? serial.incrementAndGet() : old);
    }

    synchronized Node get(Identifier dimension, long key) {
        Key k = new Key(dimension, key);
        Node n = nodes.get(k);
        if (n != null && n.revision == revision(k)) {
            cacheHits++;
            return n;
        }
        return null;
    }

    synchronized LODSectionPayload payload(Key k) {
        var data = payloads.get(k);
        var node = get(k.dimension, k.node);
        return data != null && node != null && data.revision == node.revision ? data.payload : null;
    }

    synchronized boolean request(
            Identifier dimension, long key, WorldIdentifier worldId, Registry<Biome> biomes) {
        Key k = new Key(dimension, key);
        if (queued.containsKey(k)) return true;
        if (queued.size() >= 8192) {
            rejected++;
            return false;
        }
        revision(k);
        queued.put(k, new Probe(k, worldId, biomes));
        order.add(k);
        return true;
    }

    /** Null means pending, not absence. All 64 revision reads form one atomic proof. */
    synchronized Proof proof(Identifier dimension, long group, long wanted) {
        long coverage = 0;
        long[] hashes = new long[64], revisions = new long[64];
        for (int i = 0; i < 64; i++)
            if ((wanted & (1L << i)) != 0) {
                Node node = get(dimension, DemandGroup.node(group, i));
                if (node == null) return null;
                revisions[i] = node.revision;
                if (node.present) {
                    coverage |= 1L << i;
                    hashes[i] = node.hash;
                }
            }
        return new Proof(coverage, DemandGroup.digest(wanted, revisions), hashes);
    }

    synchronized boolean current(Identifier dimension, long group, long wanted, long expected) {
        Proof proof = proof(dimension, group, wanted);
        return proof != null && proof.revision == expected;
    }

    private static final class HashDeferred extends RuntimeException {
        HashDeferred() {
            super(null, null, false, false);
        }
    }

    private static final HashDeferred HASH_DEFERRED = new HashDeferred();

    boolean computeOne(
            ServerLodEngine engine,
            java.util.function.BooleanSupplier permitHash,
            java.util.function.LongConsumer recordHash) {
        return computeOne(
                p -> {
                    WorldEngine world = engine.getOrCreate(p.worldId, p.key.dimension);
                    if (world == null) throw new IllegalStateException("LOD engine unavailable");
                    return loadNode(
                            world,
                            p,
                            permitHash,
                            elapsed -> {
                                hashesComputed++;
                                hashNanos += elapsed;
                                recordHash.accept(elapsed);
                            });
                });
    }

    static LODSectionPayload loadNode(
            WorldEngine world,
            Probe p,
            java.util.function.BooleanSupplier permitHash,
            java.util.function.LongConsumer recordHash) {
        world.acquireRef();
        try {
            var section = SectionDataAccess.acquireStored(world, p.key.node);
            if (section == null)
                return null; // A negative lookup consumes I/O budget, not a semantic-hash
                             // allowance.
            try {
                if (!permitHash.getAsBoolean()) throw HASH_DEFERRED;
                long began = System.nanoTime();
                try {
                    var snapshot = SectionDataAccess.snapshot(world, section);
                    return NativeNodeCodec.encode(
                            p.key.dimension,
                            p.key.node,
                            snapshot.data(),
                            snapshot.children(),
                            world.getMapper(),
                            p.biomes);
                } finally {
                    recordHash.accept(System.nanoTime() - began);
                }
            } finally {
                section.release();
            }
        } finally {
            world.releaseRef();
        }
    }

    boolean computeOne(java.util.function.Function<Probe, LODSectionPayload> loader) {
        Key k = order.poll();
        if (k == null) return false;
        Probe p;
        long before;
        synchronized (this) {
            p = queued.remove(k);
            if (p == null) return true;
            before = revision(k);
        }
        long started = System.nanoTime();
        LODSectionPayload payload;
        try {
            payload = loader.apply(p);
        } catch (HashDeferred deferred) {
            synchronized (this) {
                request(k.dimension, k.node, p.worldId, p.biomes);
            }
            return false;
        } catch (RuntimeException error) {
            synchronized (this) {
                request(k.dimension, k.node, p.worldId, p.biomes);
            }
            throw error;
        }
        synchronized (this) {
            if (revision(k) != before) {
                request(k.dimension, k.node, p.worldId, p.biomes);
                return true;
            }
            if (payload == null) absent++;
            nodes.put(
                    k,
                    new Node(
                            before,
                            payload == null ? 0 : payload.contentHash(),
                            payload == null ? 0 : payload.childMask(),
                            payload != null));
            if (payload != null) payloads.put(k, new CachedPayload(before, payload));
            computed++;
            computeNanos += System.nanoTime() - started;
        }
        return true;
    }

    int pending() {
        return queued.size();
    }

    synchronized int size() {
        return nodes.size();
    }

    synchronized void clear() {
        queued.clear();
        order.clear();
        nodes.clear();
        versions.clear();
        payloads.clear();
    }
}
