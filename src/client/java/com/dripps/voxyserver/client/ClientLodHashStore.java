package com.dripps.voxyserver.client;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.cortex.voxy.common.Logger;
import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

public final class ClientLodHashStore {
    private static final ClientLodHashStore INSTANCE = new ClientLodHashStore();
    private static final int MAGIC = 0x56534833;
    private static final int VERSION = com.dripps.voxyserver.util.SemanticSectionHash.VERSION;
    private static final int MAX_LOADED_WORLDS = 2;
    private static final int MAX_FILE_ENTRIES = 16_777_216;
    private static final int MAX_PENDING_ENTRIES = 131_072;

    private final Object pendingLock = new Object();
    private final Map<String, Long2LongOpenHashMap> pending = new HashMap<>();
    private final AtomicBoolean drainScheduled = new AtomicBoolean();
    private int pendingEntries;
    private final ScheduledExecutorService owner = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "VoxyServer Hash Store");
        thread.setDaemon(true);
        return thread;
    });
    private final LinkedHashMap<String, State> loaded = new LinkedHashMap<>(4, 0.75f, true);

    private static final class State {
        private final Long2LongOpenHashMap hashes;
        private int dirty;

        private State(Long2LongOpenHashMap hashes) {
            this.hashes = hashes;
        }
    }

    private ClientLodHashStore() {
        owner.scheduleWithFixedDelay(this::flushAllSafely, 30L, 30L, TimeUnit.SECONDS);
    }

    public static ClientLodHashStore get() {
        return INSTANCE;
    }

    public boolean recordBatch(String worldId, long[] keys, long[] hashes) {
        if (worldId == null || keys.length != hashes.length) return false;
        synchronized (pendingLock) {
            Long2LongOpenHashMap delta = pending.get(worldId);
            LongOpenHashSet additionsSet = new LongOpenHashSet();
            int additions = 0;
            for (long key : keys) {
                if ((delta == null || !delta.containsKey(key)) && additionsSet.add(key)) additions++;
            }
            if (pendingEntries + additions > MAX_PENDING_ENTRIES) return false;
            if (delta == null) {
                delta = new Long2LongOpenHashMap();
                pending.put(worldId, delta);
            }
            for (int i = 0; i < keys.length; i++) {
                delta.put(keys[i], hashes[i]);
            }
            pendingEntries += additions;
        }
        scheduleDrain();
        return true;
    }

    public CompletableFuture<Long2LongOpenHashMap> snapshotAsync(String worldId, BooleanSupplier current) {
        CompletableFuture<Long2LongOpenHashMap> future = new CompletableFuture<>();
        owner.execute(() -> {
            try {
                if (!current.getAsBoolean()) {
                    future.cancel(false);
                    return;
                }
                drainPending();
                if (!current.getAsBoolean()) {
                    future.cancel(false);
                    return;
                }
                future.complete(new Long2LongOpenHashMap(stateFor(worldId).hashes));
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });
        return future;
    }

    public void flush() {
        CompletableFuture.runAsync(this::flushAllSafely, owner).join();
    }

    private void scheduleDrain() {
        if (drainScheduled.compareAndSet(false, true)) {
            owner.execute(this::drainPending);
        }
    }

    private void drainPending() {
        Map<String, Long2LongOpenHashMap> deltas;
        synchronized (pendingLock) {
            deltas = new HashMap<>(pending);
            pending.clear();
            pendingEntries = 0;
            drainScheduled.set(false);
        }
        for (Map.Entry<String, Long2LongOpenHashMap> entry : deltas.entrySet()) {
            State state = stateFor(entry.getKey());
            state.hashes.putAll(entry.getValue());
            state.dirty += entry.getValue().size();
        }
        synchronized (pendingLock) {
            if (!pending.isEmpty()) scheduleDrain();
        }
    }

    private State stateFor(String worldId) {
        State state = loaded.get(worldId);
        if (state != null) return state;
        state = new State(load(worldId));
        loaded.put(worldId, state);
        while (loaded.size() > MAX_LOADED_WORLDS) {
            Iterator<Map.Entry<String, State>> iterator = loaded.entrySet().iterator();
            Map.Entry<String, State> eldest = iterator.next();
            flush(eldest.getKey(), eldest.getValue());
            iterator.remove();
        }
        return state;
    }

    private void flushAllSafely() {
        try {
            drainPending();
            for (Map.Entry<String, State> entry : loaded.entrySet()) {
                flush(entry.getKey(), entry.getValue());
            }
        } catch (Throwable throwable) {
            Logger.error("failed to flush hash sidecars", throwable);
        }
    }

    private void flush(String worldId, State state) {
        if (state.dirty == 0) return;
        Path file = fileFor(worldId);
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))) {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                out.writeInt(state.hashes.size());
                for (var entry : state.hashes.long2LongEntrySet()) {
                    out.writeLong(entry.getLongKey());
                    out.writeLong(entry.getLongValue());
                }
            }
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            state.dirty = 0;
        } catch (IOException exception) {
            Logger.error("failed to flush hash sidecar", exception);
        }
    }

    private static Long2LongOpenHashMap load(String worldId) {
        Long2LongOpenHashMap result = new Long2LongOpenHashMap();
        result.defaultReturnValue(0L);
        Path file = fileFor(worldId);
        if (!Files.exists(file)) return result;
        try {
            long length = Files.size(file);
            if (length < 12 || (length - 12) % 16 != 0L) {
                throw new IOException("invalid hash sidecar length");
            }
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
                if (in.readInt() != MAGIC || in.readInt() != VERSION) return result;
                int count = in.readInt();
                long expectedLength = 12L + (long) count * Long.BYTES * 2L;
                if (count < 0 || count > MAX_FILE_ENTRIES || expectedLength != length) {
                    throw new IOException("invalid hash sidecar entry count");
                }
                for (int i = 0; i < count; i++) {
                    result.put(in.readLong(), in.readLong());
                }
            }
        } catch (IOException exception) {
            Logger.error("failed to load hash sidecat, treating as empty", exception);
            result.clear();
        }
        return result;
    }

    private static Path fileFor(String worldId) {
        return FabricLoader.getInstance().getGameDir()
                .resolve("voxyserver").resolve("hashes").resolve(worldId + ".bin");
    }
}
