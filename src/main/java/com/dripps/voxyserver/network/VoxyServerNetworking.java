package com.dripps.voxyserver.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

public final class VoxyServerNetworking {
    public static final int PROTOCOL_VERSION = 6;

    public static void register() {
        PayloadTypeRegistry.clientboundPlay()
                .register(PreSerializedLodPayload.TYPE, PreSerializedLodPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(LODClearPayload.TYPE, LODClearPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay()
                .register(LODServerSettingsPayload.TYPE, LODServerSettingsPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay()
                .register(LODProtocolPayload.TYPE, LODProtocolPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay()
                .register(VersionSessionPayload.TYPE, VersionSessionPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay()
                .register(VersionScopePayload.TYPE, VersionScopePayload.CODEC);
        PayloadTypeRegistry.clientboundPlay()
                .register(VersionTablePayload.TYPE, VersionTablePayload.CODEC);
        PayloadTypeRegistry.clientboundPlay()
                .register(VersionStatusPayload.TYPE, VersionStatusPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay()
                .register(LODPreferencesPayload.TYPE, LODPreferencesPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay()
                .register(LODHandshakePayload.TYPE, LODHandshakePayload.CODEC);
        PayloadTypeRegistry.serverboundPlay()
                .register(LODBatchResultPayload.TYPE, LODBatchResultPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay()
                .register(VersionCancelPayload.TYPE, VersionCancelPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay()
                .register(VersionBeginPayload.TYPE, VersionBeginPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay()
                .register(VersionSummaryPayload.TYPE, VersionSummaryPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay()
                .register(VersionSelectPayload.TYPE, VersionSelectPayload.CODEC);
    }
}
