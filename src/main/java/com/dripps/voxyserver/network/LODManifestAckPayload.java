package com.dripps.voxyserver.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Acknowledges one bounded inventory page; absence never authorizes deletion. */
public record LODManifestAckPayload(Identifier dimension, long streamEpoch, long requestId,
                                    long pageId, boolean retry) implements CustomPacketPayload {
    public static final Type<LODManifestAckPayload> TYPE =
            new Type<>(Identifier.parse("voxyserver:lod_manifest_ack_v3"));
    public static final StreamCodec<RegistryFriendlyByteBuf, LODManifestAckPayload> CODEC =
            StreamCodec.of(LODManifestAckPayload::write, LODManifestAckPayload::read);

    public LODManifestAckPayload {
        if (dimension == null || streamEpoch < 0 || requestId < 0 || pageId < 0) {
            throw new IllegalArgumentException("invalid manifest acknowledgement");
        }
    }
    private static void write(RegistryFriendlyByteBuf buf, LODManifestAckPayload payload) {
        buf.writeIdentifier(payload.dimension);
        buf.writeVarLong(payload.streamEpoch);
        buf.writeVarLong(payload.requestId);
        buf.writeVarLong(payload.pageId);
        buf.writeBoolean(payload.retry);
    }
    private static LODManifestAckPayload read(RegistryFriendlyByteBuf buf) {
        return new LODManifestAckPayload(buf.readIdentifier(), buf.readVarLong(), buf.readVarLong(),
                buf.readVarLong(), buf.readBoolean());
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
