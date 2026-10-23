package com.dripps.voxyserver.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record LODStreamSessionPayload(Identifier dimension, long streamEpoch,
                                      long manifestRequestId, boolean manifestRequired)
        implements CustomPacketPayload {

    public static final Type<LODStreamSessionPayload> TYPE =
            new Type<>(Identifier.parse("voxyserver:lod_stream_session"));

    public static final StreamCodec<RegistryFriendlyByteBuf, LODStreamSessionPayload> CODEC =
            StreamCodec.of(LODStreamSessionPayload::write, LODStreamSessionPayload::read);

    public LODStreamSessionPayload {
        if (dimension == null) {
            throw new IllegalArgumentException("dimension cannot be null");
        }
        if (streamEpoch < 0L || manifestRequestId < 0L) {
            throw new IllegalArgumentException("invalid LOD stream session: epoch=" + streamEpoch
                    + ", manifestRequestId=" + manifestRequestId);
        }
    }

    private static void write(RegistryFriendlyByteBuf buf, LODStreamSessionPayload payload) {
        buf.writeIdentifier(payload.dimension);
        buf.writeVarLong(payload.streamEpoch);
        buf.writeVarLong(payload.manifestRequestId);
        buf.writeBoolean(payload.manifestRequired);
    }

    private static LODStreamSessionPayload read(RegistryFriendlyByteBuf buf) {
        Identifier dimension = buf.readIdentifier();
        long streamEpoch = buf.readVarLong();
        long manifestRequestId = buf.readVarLong();
        boolean manifestRequired = buf.readBoolean();
        if (streamEpoch < 0L || manifestRequestId < 0L) {
            throw new IllegalArgumentException("invalid LOD stream session");
        }
        return new LODStreamSessionPayload(dimension, streamEpoch, manifestRequestId, manifestRequired);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
