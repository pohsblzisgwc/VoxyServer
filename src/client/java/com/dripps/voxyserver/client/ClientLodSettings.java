package com.dripps.voxyserver.client;

import com.dripps.voxyserver.network.LODManifestPayload;
import com.dripps.voxyserver.network.LODManifestAckPayload;
import com.dripps.voxyserver.network.LODPreferencesPayload;
import com.dripps.voxyserver.network.LODStreamSessionPayload;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.resources.Identifier;

import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class ClientLodSettings {
    private static final ClientLodConfig CONFIG = ClientLodConfig.load();
    private static final long[] EMPTY_LONGS = new long[0];
    private static final ArrayBlockingQueue<ManifestChunk> manifestChunks = new ArrayBlockingQueue<>(2);
    private static final ExecutorService manifestExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "VoxyServer Manifest");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicLong connectionGeneration = new AtomicLong();
    private static final AtomicLong buildGeneration = new AtomicLong();
    private static final AtomicReference<ManifestRequest> pendingManifestRequest = new AtomicReference<>();
    private static final AtomicBoolean manifestBuildScheduled = new AtomicBoolean();

    private static int serverMaxRadius = -1;
    private static int serverMaxSections = -1;
    private static volatile boolean protocolOk;
    private static volatile boolean serverHashSyncEnabled = true;
    private static volatile Identifier sessionDimension;
    private static volatile long streamEpoch;
    private static volatile long manifestRequestId;
    private static volatile String cacheNamespace = "unbound";
    private static ManifestChunk inFlight;
    private static int manifestSendTicks;
    private static final java.util.concurrent.atomic.AtomicLong nextPageId = new java.util.concurrent.atomic.AtomicLong();
    public static String hashStoreKey(String worldId) { return cacheNamespace + "_" + worldId; }

    private static int lastReportedRadius = -1;
    private static String activeServerKey;
    private static ClientLodConfig.Preferences activePreferences = CONFIG.getPreferencesForServer(null);

    private record ManifestRequest(WorldIdentifier worldId, Identifier dimension,
                                   int playerSectionX, int playerSectionZ, int radiusSections,
                                   long streamEpoch, long requestId,
                                   long connectionGeneration, long buildGeneration,
                                   net.minecraft.core.Registry<net.minecraft.world.level.biome.Biome> biomes) {}

    private record ManifestChunk(Identifier dimension, long streamEpoch, long requestId,
                                 long[] keys, long[] hashes, int status,
                                 long connectionGeneration, long buildGeneration, long pageId) {}

    private static final class ManifestCanceledException extends RuntimeException {}

    public static void prepareForCurrentConnection() {
        serverMaxRadius = -1;
        serverMaxSections = -1;
        lastReportedRadius = -1;
        protocolOk = false;
        serverHashSyncEnabled = true;
        sessionDimension = null;
        streamEpoch = 0L;
        manifestRequestId = 0L;
        manifestChunks.clear();
        inFlight = null;
        pendingManifestRequest.set(null);
        connectionGeneration.incrementAndGet();
        buildGeneration.incrementAndGet();
        activeServerKey = resolveCurrentServerKey();
        activePreferences = CONFIG.getPreferencesForServer(activeServerKey);
    }

    public static boolean isProtocolOk() {
        return protocolOk;
    }

    public static void setProtocolOk(boolean value) {
        protocolOk = value;
    }

    public static void applyServerSettings(int maxRadius, int maxSections) {
        serverMaxRadius = maxRadius;
        serverMaxSections = maxSections;
        sendPreferences();
    }

    public static void applyHashSyncSettings(boolean enabled) {
        serverHashSyncEnabled = enabled;
        // Inventory reconciliation remains active when reuse is disabled.
    }

    public static void applyStreamSession(LODStreamSessionPayload payload) {
        cacheNamespace = payload.worldIdentity().toString() + "_semantic" + payload.hashVersion();
        nextPageId.set(0);
        sessionDimension = payload.dimension();
        streamEpoch = payload.streamEpoch();
        manifestRequestId = payload.manifestRequestId();
        long generation = buildGeneration.incrementAndGet();
        manifestChunks.clear();
        inFlight = null;
        pendingManifestRequest.set(null);
        if (!payload.manifestRequired()) return;
        requestManifestBuild(payload, generation);
    }

    public static void onClientTick() {
        if (protocolOk && Minecraft.getInstance().getConnection() != null) {
            int currentVoxyRadius = resolveVoxyPreferredRadius();
            if (currentVoxyRadius > 0 && currentVoxyRadius != lastReportedRadius) {
                lastReportedRadius = currentVoxyRadius;
                sendPreferences();
            }
        }

        manifestSendTicks++;
        if (inFlight != null && manifestSendTicks < 100) return;
        if (manifestSendTicks < 10) return;
        ManifestChunk chunk = inFlight == null ? manifestChunks.poll() : inFlight;
        if (chunk == null) return;
        if (!isCurrent(chunk)) return;
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (minecraft.getConnection() == null || level == null
                || !chunk.dimension.equals(level.dimension().identifier())) return;
        inFlight = chunk;
        manifestSendTicks = 0;
        ClientPlayNetworking.send(new LODManifestPayload(
                chunk.dimension,
                chunk.streamEpoch,
                chunk.requestId,
                chunk.keys,
                chunk.hashes,
                chunk.status,
                chunk.pageId
        ));
    }

    public static void acknowledge(LODManifestAckPayload ack) {
        ManifestChunk chunk = inFlight;
        if (chunk == null || !isCurrent(chunk) || !chunk.dimension.equals(ack.dimension())
                || chunk.streamEpoch != ack.streamEpoch() || chunk.requestId != ack.requestId()
                || chunk.pageId != ack.pageId()) return;
        manifestSendTicks = 0;
        if (!ack.retry()) inFlight = null;
    }

    public static void invalidateSession() {
        buildGeneration.incrementAndGet();
        manifestChunks.clear();
        inFlight = null;
        pendingManifestRequest.set(null);
    }

    private static void requestManifestBuild(LODStreamSessionPayload session, long generation) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        var player = minecraft.player;
        if (!protocolOk || !isEnabled()
                || serverMaxRadius < 0 || level == null || player == null
                || !session.dimension().equals(level.dimension().identifier())) {
            enqueueTerminal(session, LODManifestPayload.FAILED, generation);
            return;
        }
        WorldIdentifier worldId = WorldIdentifier.of(level);
        if (worldId == null) {
            enqueueTerminal(session, LODManifestPayload.FAILED, generation);
            return;
        }
        ManifestRequest request = new ManifestRequest(
                worldId,
                session.dimension(),
                (player.getBlockX() >> 4) >> 1,
                (player.getBlockZ() >> 4) >> 1,
                Math.max(0, effectiveRadius()) >> 1,
                session.streamEpoch(),
                session.manifestRequestId(),
                connectionGeneration.get(),
                generation,
                level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BIOME)
        );
        pendingManifestRequest.set(request);
        scheduleManifestBuild();
    }

    private static void scheduleManifestBuild() {
        if (!manifestBuildScheduled.compareAndSet(false, true)) return;
        manifestExecutor.execute(ClientLodSettings::startLatestManifestBuild);
    }

    private static void startLatestManifestBuild() {
        ManifestRequest request = pendingManifestRequest.getAndSet(null);
        if (request == null || !isCurrent(request)) {
            finishManifestBuild();
            return;
        }
        try {
            buildManifest(request);
        } finally {
            finishManifestBuild();
        }
    }

    private static void finishManifestBuild() {
        manifestBuildScheduled.set(false);
        if (pendingManifestRequest.get() != null) scheduleManifestBuild();
    }

    private static void buildManifest(ManifestRequest request) {
        WorldEngine engine = request.worldId.getOrCreateEngine();
        if (engine == null || !engine.isLive()) {
            enqueueTerminal(request, LODManifestPayload.FAILED);
            return;
        }
        engine.acquireRef();
        try {
            long[] keys = new long[LODManifestPayload.MAX_ENTRIES];
            long[] hashes = new long[LODManifestPayload.MAX_ENTRIES];
            int[] count = new int[1];
            var budget = new com.dripps.voxyserver.util.HashWorkBudget();
            String storeKey = hashStoreKey(request.worldId.getWorldId());
            engine.storage.iteratePositions(0, key -> {
                if (!isCurrent(request)) throw new ManifestCanceledException();
                int sectionX = WorldEngine.getX(key);
                int sectionZ = WorldEngine.getZ(key);
                if (Math.abs(sectionX - request.playerSectionX) > request.radiusSections
                        || Math.abs(sectionZ - request.playerSectionZ) > request.radiusSections) return;
                while (!budget.tryAcquire(System.nanoTime() / 50_000_000L, 20, 32,
                        TimeUnit.MILLISECONDS.toNanos(20))) {
                    if (!isCurrent(request) || Thread.currentThread().isInterrupted()) throw new ManifestCanceledException();
                    java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(25));
                }
                long started = System.nanoTime();
                var section = engine.acquireIfExists(key);
                if (section == null) {
                    budget.recordNanos(System.nanoTime() - started);
                    return;
                }
                long hash;
                try {
                    hash = com.dripps.voxyserver.util.SemanticPalette.hashStored(section.copyData(), engine.getMapper(), request.biomes);
                } finally {
                    section.release();
                    budget.recordNanos(System.nanoTime() - started);
                }
                if (!isCurrent(request)) throw new ManifestCanceledException();
                ClientLodHashStore.get().recordBatch(storeKey, new long[]{key}, new long[]{hash});
                keys[count[0]] = key;
                hashes[count[0]] = hash;
                count[0]++;
                if (count[0] == keys.length) {
                    enqueueChunk(request, Arrays.copyOf(keys, count[0]),
                            Arrays.copyOf(hashes, count[0]), LODManifestPayload.PART);
                    count[0] = 0;
                }
            });
            if (!isCurrent(request)) return;
            enqueueChunk(
                    request,
                    count[0] == 0 ? EMPTY_LONGS : Arrays.copyOf(keys, count[0]),
                    count[0] == 0 ? EMPTY_LONGS : Arrays.copyOf(hashes, count[0]),
                    LODManifestPayload.COMPLETE
            );
        } catch (ManifestCanceledException ignored) {
        } catch (Throwable throwable) {
            me.cortex.voxy.common.Logger.error("voxyserver manifest build failed", throwable);
            if (isCurrent(request)) enqueueTerminal(request, LODManifestPayload.FAILED);
        } finally {
            engine.releaseRef();
        }
    }

    private static void enqueueChunk(ManifestRequest request, long[] keys, long[] hashes, int status) {
        ManifestChunk chunk = new ManifestChunk(
                request.dimension, request.streamEpoch, request.requestId,
                keys, hashes, status, request.connectionGeneration, request.buildGeneration, nextPageId.getAndIncrement());
        while (isCurrent(request)) {
            try {
                if (manifestChunks.offer(chunk, 100L, TimeUnit.MILLISECONDS)) return;
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                throw new ManifestCanceledException();
            }
        }
        throw new ManifestCanceledException();
    }

    private static void enqueueTerminal(ManifestRequest request, int status) {
        enqueueChunk(request, EMPTY_LONGS, EMPTY_LONGS, status);
    }

    private static void enqueueTerminal(LODStreamSessionPayload session, int status, long generation) {
        ManifestChunk chunk = new ManifestChunk(
                session.dimension(), session.streamEpoch(), session.manifestRequestId(),
                EMPTY_LONGS, EMPTY_LONGS, status, connectionGeneration.get(), generation, nextPageId.getAndIncrement());
        manifestChunks.offer(chunk);
    }

    private static boolean isCurrent(ManifestRequest request) {
        return protocolOk
                && connectionGeneration.get() == request.connectionGeneration
                && buildGeneration.get() == request.buildGeneration
                && streamEpoch == request.streamEpoch
                && manifestRequestId == request.requestId
                && request.dimension.equals(sessionDimension);
    }

    private static boolean isCurrent(ManifestChunk chunk) {
        return connectionGeneration.get() == chunk.connectionGeneration
                && buildGeneration.get() == chunk.buildGeneration
                && streamEpoch == chunk.streamEpoch
                && manifestRequestId == chunk.requestId
                && chunk.dimension.equals(sessionDimension);
    }

    public static int resolveVoxyPreferredRadius() {
        try {
            if (me.cortex.voxy.client.config.VoxyConfig.CONFIG != null) {
                float secDist = me.cortex.voxy.client.config.VoxyConfig.CONFIG.sectionRenderDistance;
                if (secDist > 0) {
                    return Math.min(4096, (int) Math.ceil(secDist * 2.0f));
                }
            }
        } catch (Throwable ignored) {}
        return activePreferences.preferredRadius;
    }

    private static int effectiveRadius() {
        int preferred = resolveVoxyPreferredRadius();
        return preferred <= 0 ? serverMaxRadius : Math.min(preferred, serverMaxRadius);
    }

    public static void reset() {
        serverMaxRadius = -1;
        serverMaxSections = -1;
        lastReportedRadius = -1;
        protocolOk = false;
        serverHashSyncEnabled = true;
        sessionDimension = null;
        streamEpoch = 0L;
        manifestRequestId = 0L;
        manifestChunks.clear();
        inFlight = null;
        pendingManifestRequest.set(null);
        connectionGeneration.incrementAndGet();
        buildGeneration.incrementAndGet();
        activeServerKey = null;
        activePreferences = CONFIG.getPreferencesForServer(null);
    }

    public static boolean hasActiveServerProfile() {
        return activeServerKey != null;
    }

    public static boolean isEnabled() {
        return activePreferences.enabled;
    }

    public static int getServerMaxRadius() {
        return serverMaxRadius;
    }

    public static int getServerMaxSections() {
        return serverMaxSections;
    }

    public static int getPreferredRadius() {
        return resolveVoxyPreferredRadius();
    }

    public static int getPreferredMaxSections() {
        return activePreferences.preferredMaxSections;
    }

    public static void setEnabled(boolean value) {
        activePreferences.enabled = value;
        if (!value) ClientLodReceiver.disableRemoteIngest();
    }

    public static void setPreferredRadius(int radius) {
        activePreferences.preferredRadius = Math.max(0, radius);
    }

    public static void setPreferredMaxSections(int maxSections) {
        activePreferences.preferredMaxSections = Math.max(0, maxSections);
    }

    public static void saveAndSendPreferences() {
        saveActiveProfile();
        sendPreferences();
    }

    public static void sendPreferences() {
        if (Minecraft.getInstance().getConnection() == null) return;
        int radius = resolveVoxyPreferredRadius();
        lastReportedRadius = radius;
        ClientPlayNetworking.send(new LODPreferencesPayload(
                activePreferences.enabled,
                radius,
                0));
    }

    private static void saveActiveProfile() {
        if (activeServerKey != null) CONFIG.savePreferencesForServer(activeServerKey, activePreferences);
    }

    private static String resolveCurrentServerKey() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.hasSingleplayerServer() || minecraft.isLocalServer()) return null;
        ServerData currentServer = minecraft.getCurrentServer();
        if (currentServer == null || currentServer.isLan()
                || currentServer.ip == null || currentServer.ip.isBlank()) return null;
        ServerAddress address = ServerAddress.parseString(currentServer.ip);
        String host = address.getHost();
        if (host == null || host.isBlank()) return null;
        return host.toLowerCase(Locale.ROOT) + ":" + address.getPort();
    }
}
