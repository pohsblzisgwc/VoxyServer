package com.dripps.voxyserver.client;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

import net.fabricmc.loader.api.FabricLoader;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Metadata only; reset/invalidation never modifies native Voxy terrain. I/O is on demand worker.
 */
public final class ClientVersionStore {
    private final Path path, clean;
    private final Long2IntOpenHashMap versions = new Long2IntOpenHashMap();
    private boolean dirty;
    private long revision;

    public ClientVersionStore(String namespace) {
        if (!namespace.matches("[a-zA-Z0-9_\\-]+"))
            throw new IllegalArgumentException("invalid version namespace");
        path =
                FabricLoader.getInstance()
                        .getGameDir()
                        .resolve("voxyserver/versions")
                        .resolve(namespace + ".bin");
        clean = path.resolveSibling(path.getFileName() + ".clean");
    }

    public synchronized void load() throws IOException {
        boolean trusted = Files.exists(clean);
        Files.deleteIfExists(clean);
        if (!Files.exists(path) || !trusted) return;
        try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            if (in.readInt() != 0x56534336) throw new IOException("client version format");
            int count = in.readInt();
            if (count < 0 || count > 16_777_216)
                throw new IOException("client version entry count");
            for (int i = 0; i < count; i++) {
                long key = in.readLong();
                int v = in.readInt();
                if ((key & 15) != 0
                        || me.cortex.voxy.common.world.WorldEngine.getLevel(key) != 0
                        || v < 1
                        || versions.putIfAbsent(key, v) != 0)
                    throw new IOException("client version entry");
            }
            if (in.read() != -1) throw new IOException("trailing client versions");
        } catch (IOException e) {
            versions.clear();
            throw e;
        }
    }

    public Path path() {
        return path;
    }

    public void markClean() throws IOException {
        save();
        if (Files.exists(path)) Files.writeString(clean, "native storage closed");
    }

    public synchronized int get(long key) {
        return versions.get(key);
    }

    public synchronized void record(long key, int version) {
        if (version < 1) throw new IllegalArgumentException("unconfirmed version");
        versions.put(key, version);
        dirty = true;
        revision++;
    }

    public synchronized void invalidate(long key) {
        if (versions.remove(key) != 0) {
            dirty = true;
            revision++;
        }
    }

    public synchronized Long2IntOpenHashMap snapshot() {
        return versions.clone();
    }

    public void save() throws IOException {
        Long2IntOpenHashMap copy;
        long captured;
        synchronized (this) {
            if (!dirty) return;
            copy = versions.clone();
            captured = revision;
        }
        Files.createDirectories(path.getParent());
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        try (var out =
                new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))) {
            out.writeInt(0x56534336);
            out.writeInt(copy.size());
            for (var e : copy.long2IntEntrySet()) {
                out.writeLong(e.getLongKey());
                out.writeInt(e.getIntValue());
            }
        }
        try {
            Files.move(
                    temp,
                    path,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
        }
        synchronized (this) {
            if (revision == captured) dirty = false;
        }
    }
}
