package com.dripps.voxyserver.client;

import com.dripps.voxyserver.client.service.IVoxyServerIngestAccess;
import com.dripps.voxyserver.client.service.RemoteIngestService;
import com.dripps.voxyserver.network.LODBatchResultPayload;
import com.dripps.voxyserver.network.LODClearPayload;
import com.dripps.voxyserver.network.LODHandshakePayload;
import com.dripps.voxyserver.network.LODProtocolPayload;
import com.dripps.voxyserver.network.LODServerSettingsPayload;
import com.dripps.voxyserver.network.PreSerializedLodPayload;
import com.dripps.voxyserver.network.VersionScopePayload;
import com.dripps.voxyserver.network.VersionSessionPayload;
import com.dripps.voxyserver.network.VersionStatusPayload;
import com.dripps.voxyserver.network.VersionTablePayload;
import com.dripps.voxyserver.network.VoxyServerNetworking;

import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.VoxyInstance;
import me.cortex.voxy.commonImpl.WorldIdentifier;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class ClientLodReceiver {
    private static final int MAX_INBOUND_BATCHES = 64;
    private static final long MAX_INBOUND_BYTES = 64L * 1024L * 1024L;
    private static final Object inboundLock = new Object();
    private static final ArrayDeque<Inbound> inboundQueue = new ArrayDeque<>();
    private static final AtomicBoolean drainScheduled = new AtomicBoolean();
    private static final AtomicLong connectionGeneration = new AtomicLong();
    private static int reservedBatches;
    private static long reservedBytes;
    private static volatile long networkEpoch;
    private static volatile Identifier networkDimension;

    private static final class Inbound {
        private final PreSerializedLodPayload payload;
        private final long connectionGeneration;
        private final AtomicBoolean released = new AtomicBoolean();

        private Inbound(PreSerializedLodPayload payload, long connectionGeneration) {
            this.payload = payload;
            this.connectionGeneration = connectionGeneration;
        }
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> ClientLodSettings.onClientTick());

        ClientPlayConnectionEvents.JOIN.register(
                (handler, sender, client) -> {
                    connectionGeneration.incrementAndGet();
                    networkEpoch = 0L;
                    networkDimension = null;
                    clearQueuedInbound(false);
                    ClientLodSettings.prepareForCurrentConnection();
                    if (ClientPlayNetworking.canSend(LODHandshakePayload.TYPE)) {
                        ClientLodSettings.sendHandshake();
                    }
                });

        ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) -> {
                    connectionGeneration.incrementAndGet();
                    networkEpoch = 0L;
                    networkDimension = null;
                    clearQueuedInbound(false);
                    ClientLodSettings.reset();
                    setIngestSession(null, 0L);
                });

        ClientPlayNetworking.registerGlobalReceiver(
                LODProtocolPayload.TYPE,
                (payload, context) ->
                        context.client()
                                .execute(
                                        () -> {
                                            int serverProtocol = payload.protocol();
                                            int clientProtocol =
                                                    VoxyServerNetworking.PROTOCOL_VERSION;
                                            if (serverProtocol == clientProtocol) {
                                                ClientLodSettings.applyProtocol(serverProtocol);
                                            } else {
                                                ClientLodSettings.applyProtocol(serverProtocol);
                                                disableRemoteIngest();
                                                if (serverProtocol > 0
                                                        && serverProtocol < clientProtocol)
                                                    tellPlayer(serverOutOfDateMessage());
                                            }
                                        }));

        ClientPlayNetworking.registerGlobalReceiver(
                LODServerSettingsPayload.TYPE,
                (payload, context) ->
                        context.client()
                                .execute(
                                        () ->
                                                ClientLodSettings.applyServerSettings(
                                                        payload.maxLodStreamRadius(),
                                                        payload.maxSectionsPerTick())));

        ClientPlayNetworking.registerGlobalReceiver(
                VersionSessionPayload.TYPE,
                (payload, context) -> {
                    boolean changed =
                            networkEpoch != payload.epoch()
                                    || !Objects.equals(networkDimension, payload.dimension());
                    networkEpoch = payload.epoch();
                    networkDimension = payload.dimension();
                    context.client()
                            .execute(
                                    () -> {
                                        if (networkEpoch != payload.epoch()
                                                || !Objects.equals(
                                                        networkDimension, payload.dimension()))
                                            return;
                                        clearStaleQueuedInbound(payload.epoch());
                                        if (changed)
                                            setIngestSession(payload.dimension(), payload.epoch());
                                        ClientLodSettings.applyStreamSession(payload);
                                    });
                });

        ClientPlayNetworking.registerGlobalReceiver(
                PreSerializedLodPayload.TYPE,
                (payload, context) -> {
                    if (!ClientLodSettings.isProtocolOk()
                            || payload.streamEpoch() != networkEpoch
                            || networkDimension == null) {
                        sendResult(
                                context.client(),
                                new LODBatchResultPayload(
                                        payload.streamEpoch(),
                                        payload.batchId(),
                                        LODBatchResultPayload.STALE),
                                connectionGeneration.get());
                        return;
                    }
                    long generation = connectionGeneration.get();
                    Inbound inbound = new Inbound(payload, generation);
                    if (!reserveInbound(inbound)) {
                        sendResult(
                                context.client(),
                                new LODBatchResultPayload(
                                        payload.streamEpoch(),
                                        payload.batchId(),
                                        LODBatchResultPayload.OVERFLOW),
                                generation);
                        return;
                    }
                    scheduleDrain(context.client());
                });

        ClientPlayNetworking.registerGlobalReceiver(
                VersionScopePayload.TYPE, (payload, context) -> ClientDemand.receive(payload));
        ClientPlayNetworking.registerGlobalReceiver(
                VersionTablePayload.TYPE, (payload, context) -> ClientDemand.receive(payload));
        ClientPlayNetworking.registerGlobalReceiver(
                VersionStatusPayload.TYPE, (payload, context) -> ClientDemand.receive(payload));

        ClientPlayNetworking.registerGlobalReceiver(
                LODClearPayload.TYPE,
                (payload, context) -> context.client().execute(() -> handleClear(payload)));
    }

    private static boolean reserveInbound(Inbound inbound) {
        synchronized (inboundLock) {
            int bytes = inbound.payload.data().length;
            if (reservedBatches >= MAX_INBOUND_BATCHES
                    || reservedBytes + bytes > MAX_INBOUND_BYTES) {
                return false;
            }
            reservedBatches++;
            reservedBytes += bytes;
            inboundQueue.addLast(inbound);
            return true;
        }
    }

    private static void scheduleDrain(Minecraft client) {
        if (drainScheduled.compareAndSet(false, true)) {
            client.execute(() -> drainInbound(client));
        }
    }

    private static void drainInbound(Minecraft client) {
        try {
            while (true) {
                Inbound inbound;
                synchronized (inboundLock) {
                    inbound = inboundQueue.pollFirst();
                }
                if (inbound == null) return;
                dispatchInbound(client, inbound);
            }
        } finally {
            drainScheduled.set(false);
            synchronized (inboundLock) {
                if (!inboundQueue.isEmpty()) scheduleDrain(client);
            }
        }
    }

    private static void dispatchInbound(Minecraft client, Inbound inbound) {
        ClientLevel level = client.level;
        Identifier dimension = level == null ? null : level.dimension().identifier();
        if (inbound.connectionGeneration != connectionGeneration.get()
                || inbound.payload.streamEpoch() != networkEpoch
                || !Objects.equals(dimension, networkDimension)) {
            completeInbound(
                    client,
                    inbound,
                    new LODBatchResultPayload(
                            inbound.payload.streamEpoch(),
                            inbound.payload.batchId(),
                            LODBatchResultPayload.STALE));
            return;
        }

        VoxyInstance instance = VoxyCommon.getInstance();
        WorldIdentifier worldId = level == null ? null : WorldIdentifier.of(level);
        if (instance == null || worldId == null) {
            completeInbound(
                    client,
                    inbound,
                    new LODBatchResultPayload(
                            inbound.payload.streamEpoch(),
                            inbound.payload.batchId(),
                            LODBatchResultPayload.FAILURE));
            return;
        }

        RemoteIngestService service =
                ((IVoxyServerIngestAccess) instance).voxyserver$getRemoteIngestService();
        if (service == null || !service.isLive()) {
            completeInbound(
                    client,
                    inbound,
                    new LODBatchResultPayload(
                            inbound.payload.streamEpoch(),
                            inbound.payload.batchId(),
                            LODBatchResultPayload.FAILURE));
            return;
        }

        service.startSession(dimension, inbound.payload.streamEpoch());
        boolean accepted =
                service.enqueueIngest(
                        worldId,
                        dimension,
                        inbound.payload,
                        level.registryAccess(),
                        result -> completeInbound(client, inbound, result));
        if (!accepted) {
            completeInbound(
                    client,
                    inbound,
                    new LODBatchResultPayload(
                            inbound.payload.streamEpoch(),
                            inbound.payload.batchId(),
                            LODBatchResultPayload.OVERFLOW));
        }
    }

    private static void completeInbound(
            Minecraft client, Inbound inbound, LODBatchResultPayload result) {
        releaseInbound(inbound);
        sendResult(client, result, inbound.connectionGeneration);
    }

    private static void releaseInbound(Inbound inbound) {
        if (!inbound.released.compareAndSet(false, true)) return;
        synchronized (inboundLock) {
            reservedBatches--;
            reservedBytes -= inbound.payload.data().length;
        }
    }

    private static void clearQueuedInbound(boolean sendStale) {
        Minecraft client = Minecraft.getInstance();
        while (true) {
            Inbound inbound;
            synchronized (inboundLock) {
                inbound = inboundQueue.pollFirst();
            }
            if (inbound == null) return;
            releaseInbound(inbound);
            if (sendStale) {
                sendResult(
                        client,
                        new LODBatchResultPayload(
                                inbound.payload.streamEpoch(),
                                inbound.payload.batchId(),
                                LODBatchResultPayload.STALE),
                        inbound.connectionGeneration);
            }
        }
    }

    private static void clearStaleQueuedInbound(long epoch) {
        Minecraft client = Minecraft.getInstance();
        ArrayDeque<Inbound> stale = new ArrayDeque<>();
        synchronized (inboundLock) {
            var iterator = inboundQueue.iterator();
            while (iterator.hasNext()) {
                Inbound inbound = iterator.next();
                if (inbound.payload.streamEpoch() != epoch) {
                    iterator.remove();
                    stale.addLast(inbound);
                }
            }
        }
        for (Inbound inbound : stale) {
            releaseInbound(inbound);
            sendResult(
                    client,
                    new LODBatchResultPayload(
                            inbound.payload.streamEpoch(),
                            inbound.payload.batchId(),
                            LODBatchResultPayload.STALE),
                    inbound.connectionGeneration);
        }
    }

    private static void sendResult(
            Minecraft client, LODBatchResultPayload result, long generation) {
        client.execute(
                () -> {
                    if (generation != connectionGeneration.get() || client.getConnection() == null)
                        return;
                    if (ClientPlayNetworking.canSend(LODBatchResultPayload.TYPE)) {
                        ClientPlayNetworking.send(result);
                    }
                });
    }

    static void disableRemoteIngest() {
        networkEpoch = 0L;
        networkDimension = null;
        clearQueuedInbound(true);
        setIngestSession(null, 0L);
    }

    private static void setIngestSession(Identifier dimension, long epoch) {
        VoxyInstance instance = VoxyCommon.getInstance();
        if (instance == null) return;
        RemoteIngestService service =
                ((IVoxyServerIngestAccess) instance).voxyserver$getRemoteIngestService();
        if (service != null) service.startSession(dimension, epoch);
    }

    private static void tellPlayer(Component message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.gui.hud.getChat().addClientSystemMessage(message);
        }
    }

    private static Component serverOutOfDateMessage() {
        return Component.literal("[VoxyServer] ")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                .append(
                        Component.literal(
                                        "this server's VoxyServer is out of date. LODs disabled,"
                                            + " ask the admin to update.")
                                .withStyle(ChatFormatting.RED));
    }

    private static void handleClear(LODClearPayload payload) {
        if (payload.dimension().isPresent()
                && !Objects.equals(payload.dimension().get(), networkDimension)) return;
        // A legacy clear signal invalidates verification and in-flight work only.
        // Server absence must never erase locally acquired Voxy terrain.
        clearQueuedInbound(true);
        setIngestSession(null, 0L);
        ClientLodSettings.invalidateSession();
    }
}
