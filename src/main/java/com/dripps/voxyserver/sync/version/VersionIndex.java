package com.dripps.voxyserver.sync.version;

import net.minecraft.resources.Identifier;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.LongConsumer;

/**
 * Persistent sparse LOD0 versions. Caller serializes data publication with the native world lock.
 */
public final class VersionIndex {
    public record Root(Identifier dimension, long key) {}

    private final Map<Root, int[]> roots = new HashMap<>();
    private final Set<Identifier> initialized = new HashSet<>();
    private final Set<Identifier> initializing = new HashSet<>();
    private final Map<Root, Integer> revisions = new HashMap<>();

    private record DigestKey(Root root, int mask) {}

    private record CachedDigest(int revision, long digest) {}

    private final com.dripps.voxyserver.util.BoundedLruCache<DigestKey, CachedDigest> digests =
            new com.dripps.voxyserver.util.BoundedLruCache<>(65536);
    private final Map<Leaf, Integer> pending = new HashMap<>();
    private final ThreadLocal<Set<Leaf>> batch = new ThreadLocal<>();

    private record Leaf(Identifier dimension, long key) {}

    private final Path path, clean, journal;
    private volatile UUID namespace = UUID.randomUUID();
    private final boolean cleanRestart;
    private volatile boolean loaded;
    private boolean dirty, checkpoint;
    private volatile long increments, digestComputations;
    private volatile int nodes;

    public int nodeCount() {
        return nodes;
    }

    public long digestComputations() {
        return digestComputations;
    }

    private static final int MAGIC = 0x56535636, MAX_ENTRIES = 16_777_216;

    public VersionIndex(Path path) throws IOException {
        this.path = path;
        clean = path.resolveSibling(path.getFileName() + ".clean");
        journal = path.resolveSibling(path.getFileName() + ".journal");
        cleanRestart = Files.exists(path) && Files.exists(clean);
        // Only the fixed-size authority header is needed to advertise a session at startup.
        if (cleanRestart)
            try (var in = new DataInputStream(Files.newInputStream(path))) {
                if (in.readInt() != MAGIC) throw new IOException("invalid version index header");
                namespace = new UUID(in.readLong(), in.readLong());
            }
        Files.deleteIfExists(clean);
    }

    /** Called by the maintenance/import worker; no full index I/O on a Minecraft tick. */
    public synchronized void ensureLoaded() throws IOException {
        if (loaded) return;
        if (cleanRestart) {
            load();
            if (Files.exists(journal))
                try (var in =
                        new DataInputStream(
                                new BufferedInputStream(Files.newInputStream(journal)))) {
                    while (in.available() > 0) {
                        var dim = Identifier.parse(in.readUTF());
                        long key = in.readLong();
                        int value = in.readInt();
                        if ((key & 15) != 0
                                || me.cortex.voxy.common.world.WorldEngine.getLevel(key) != 0
                                || value < 1) throw new IOException("invalid version journal");
                        int[] values =
                                roots.computeIfAbsent(
                                        new Root(dim, VersionScope.root(key)), k -> new int[4096]);
                        if (values[VersionScope.slot(key)] == 0) nodes++;
                        values[VersionScope.slot(key)] = value;
                    }
                }
        } else {
            roots.clear();
            initialized.clear();
            dirty = true;
            checkpoint = true;
            Files.deleteIfExists(journal);
        }
        loaded = true;
    }

    private void ready() {
        try {
            ensureLoaded();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load version inventory", e);
        }
    }

    public UUID namespace() {
        return namespace;
    }

    public long increments() {
        return increments;
    }

    public int rootCount() {
        if (!loaded) return 0;
        synchronized (this) {
            return roots.size();
        }
    }

    public synchronized int get(Identifier dim, long key) {
        ready();
        int[] v = roots.get(new Root(dim, VersionScope.root(key)));
        return v == null ? 0 : v[VersionScope.slot(key)];
    }

    public synchronized int[] snapshot(Identifier dim, long root, VersionScope scope) {
        ready();
        int[] found = roots.get(new Root(dim, root));
        int[] out = found == null ? new int[4096] : found.clone();
        if (scope != null)
            for (int i = 0; i < 4096; i++)
                if (out[i] != 0 && !scope.contains(VersionScope.node(root, i))) out[i] = 0;
        return out;
    }

    public synchronized long digest(Identifier dim, long root, VersionScope scope) {
        ready();
        Root r = new Root(dim, root);
        int[] values = roots.get(r);
        if (values == null) return 0;
        var key = new DigestKey(r, scope.mask(root));
        int revision = revisions.getOrDefault(r, 0);
        var cached = digests.get(key);
        if (cached != null && cached.revision == revision) return cached.digest;
        digestComputations++;
        long digest = VersionDigest.compute(values, scope, root);
        digests.put(key, new CachedDigest(revision, digest));
        return digest;
    }

    public void bootstrap(Identifier dim, java.util.function.Consumer<LongConsumer> inventory) {
        ready();
        synchronized (this) {
            while (initializing.contains(dim))
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("inventory interrupted", e);
                }
            if (initialized.contains(dim)) return;
            initializing.add(dim);
        }
        boolean success = false;
        try {
            inventory.accept(
                    key -> {
                        if ((key & 15) == 0
                                && me.cortex.voxy.common.world.WorldEngine.getLevel(key) == 0)
                            synchronized (this) {
                                Root root = new Root(dim, VersionScope.root(key));
                                int[] v = roots.computeIfAbsent(root, k -> new int[4096]);
                                if (v[VersionScope.slot(key)] == 0) {
                                    nodes++;
                                    v[VersionScope.slot(key)] = 1;
                                    revisions.merge(root, 1, Integer::sum);
                                }
                            }
                    });
            success = true;
        } finally {
            synchronized (this) {
                initializing.remove(dim);
                if (success) {
                    initialized.add(dim);
                    dirty = true;
                    checkpoint = true;
                }
                notifyAll();
            }
        }
    }

    public void beginBatch() {
        if (batch.get() != null) throw new IllegalStateException("nested version batch");
        batch.set(new HashSet<>());
    }

    public void changed(Identifier dim, long key) {
        if (me.cortex.voxy.common.world.WorldEngine.getLevel(key) != 0) return;
        var pending = batch.get();
        if (pending != null) pending.add(new Leaf(dim, key));
        else increment(dim, key);
    }

    public void endBatch() {
        var pending = batch.get();
        if (pending == null) return;
        batch.remove();
        for (var leaf : pending) increment(leaf.dimension, leaf.key);
    }

    private synchronized void increment(Identifier dim, long key) {
        ready();
        int[] v = roots.computeIfAbsent(new Root(dim, VersionScope.root(key)), k -> new int[4096]);
        int slot = VersionScope.slot(key);
        if (v[slot] == 0) nodes++;
        if (v[slot] == Integer.MAX_VALUE) {
            // Unreachable at normal cadence; change authority rather than silently alias version 1.
            namespace = UUID.randomUUID();
            v[slot] = 1;
            checkpoint = true;
        } else v[slot]++;
        revisions.merge(new Root(dim, VersionScope.root(key)), 1, Integer::sum);
        pending.put(new Leaf(dim, key), v[slot]);
        increments++;
        dirty = true;
    }

    public void save() throws IOException {
        ensureLoaded();
        Map<Leaf, Integer> changes;
        Map<Root, int[]> references = null;
        Set<Identifier> dimensions = null;
        UUID authority;
        boolean full;
        synchronized (this) {
            if (!dirty && Files.exists(path)) return;
            full =
                    checkpoint
                            || !Files.exists(path)
                            || (Files.exists(journal) && Files.size(journal) >= 4 * 1024 * 1024);
            changes = new HashMap<>(pending);
            pending.clear();
            authority = namespace;
            if (full) {
                references = new HashMap<>(roots);
                dimensions = Set.copyOf(initialized);
            }
            dirty = false;
            checkpoint = false;
        }
        try {
            Files.createDirectories(path.getParent());
            if (!full) {
                try (var out =
                        new DataOutputStream(
                                new BufferedOutputStream(
                                        Files.newOutputStream(
                                                journal,
                                                StandardOpenOption.CREATE,
                                                StandardOpenOption.APPEND)))) {
                    for (var e : changes.entrySet()) {
                        out.writeUTF(e.getKey().dimension.toString());
                        out.writeLong(e.getKey().key);
                        out.writeInt(e.getValue());
                    }
                }
                return;
            }
            Path temp = path.resolveSibling(path.getFileName() + ".tmp");
            try (var out =
                    new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))) {
                out.writeInt(MAGIC);
                out.writeLong(authority.getMostSignificantBits());
                out.writeLong(authority.getLeastSignificantBits());
                out.writeInt(dimensions.size());
                for (var dim : dimensions) out.writeUTF(dim.toString());
                out.writeInt(references.size());
                for (var e : references.entrySet()) {
                    int[] values;
                    synchronized (this) {
                        values = e.getValue().clone();
                    }
                    out.writeUTF(e.getKey().dimension.toString());
                    out.writeLong(e.getKey().key);
                    int count = 0;
                    for (int v : values) if (v > 0) count++;
                    out.writeShort(count);
                    for (int i = 0; i < 4096; i++)
                        if (values[i] > 0) {
                            out.writeShort(i);
                            out.writeInt(values[i]);
                        }
                }
            }
            try {
                Files.move(
                        temp,
                        path,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.deleteIfExists(journal);
        } catch (IOException failure) {
            synchronized (this) {
                for (var e : changes.entrySet()) pending.putIfAbsent(e.getKey(), e.getValue());
                dirty = true;
                checkpoint = true;
            }
            throw failure;
        }
    }

    /** Only after native storage and its save workers have successfully stopped. */
    public void markClean() throws IOException {
        if (!loaded && cleanRestart) {
            Files.writeString(clean, namespace.toString());
            return;
        }
        save();
        Files.writeString(clean, namespace.toString());
    }

    private void load() throws IOException {
        try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            if (in.readInt() != MAGIC) throw new IOException("invalid version index header");
            namespace = new UUID(in.readLong(), in.readLong());
            int dimensions = in.readInt();
            if (dimensions < 0 || dimensions > 4096)
                throw new IOException("version dimension count");
            for (int i = 0; i < dimensions; i++) initialized.add(Identifier.parse(in.readUTF()));
            int n = in.readInt(), entries = 0;
            if (n < 0 || n > MAX_ENTRIES) throw new IOException("version root count");
            for (int i = 0; i < n; i++) {
                var dim = Identifier.parse(in.readUTF());
                long root = in.readLong();
                if ((root & 15) != 0 || me.cortex.voxy.common.world.WorldEngine.getLevel(root) != 4)
                    throw new IOException("version root key");
                int count = in.readUnsignedShort();
                if (count > 4096 || (entries += count) > MAX_ENTRIES)
                    throw new IOException("version entry count");
                int[] v = new int[4096];
                for (int j = 0; j < count; j++) {
                    int slot = in.readUnsignedShort(), value = in.readInt();
                    if (slot >= 4096 || value <= 0 || v[slot] != 0)
                        throw new IOException("version entry");
                    v[slot] = value;
                }
                if (roots.put(new Root(dim, root), v) != null)
                    throw new IOException("duplicate version root");
            }
            if (in.read() != -1) throw new IOException("trailing version data");
            nodes = entries;
        } catch (IllegalArgumentException e) {
            throw new IOException("invalid version index", e);
        }
    }
}
