package com.dripps.voxyserver.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record LODStreamSessionPayload(Identifier dimension, long streamEpoch,
                                      long manifestRequestId, boolean manifestRequired,
                                      java.util.UUID worldIdentity, int hashVersion)
        implements CustomPacketPayload {

    public static final Type<LODStreamSessionPayload> TYPE =
            new Type<>(Identifier.parse("voxyserver:lod_stream_session_v3"));

    public static final StreamCodec<RegistryFriendlyByteBuf, LODStreamSessionPayload> CODEC =
            StreamCodec.of(LODStreamSessionPayload::write, LODStreamSessionPayload::read);

    public LODStreamSessionPayload(Identifier dimension, long streamEpoch, long manifestRequestId, boolean manifestRequired) {
        this(dimension, streamEpoch, manifestRequestId, manifestRequired, new java.util.UUID(0, 0),
                com.dripps.voxyserver.util.SemanticSectionHash.VERSION);
    }

    public LODStreamSessionPayload {
        if (dimension == null || worldIdentity == null || hashVersion != com.dripps.voxyserver.util.SemanticSectionHash.VERSION)
            throw new IllegalArgumentException("incompatible semantic hash session");
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
        buf.writeLong(payload.worldIdentity.getMostSignificantBits());
        buf.writeLong(payload.worldIdentity.getLeastSignificantBits());
        buf.writeVarInt(payload.hashVersion);
    }

    private static LODStreamSessionPayload read(RegistryFriendlyByteBuf buf) {
        Identifier dimension = buf.readIdentifier();
        long streamEpoch = buf.readVarLong();
        long manifestRequestId = buf.readVarLong();
        boolean manifestRequired = buf.readBoolean();
        if (streamEpoch < 0L || manifestRequestId < 0L) {
            throw new IllegalArgumentException("invalid LOD stream session");
        }
        java.util.UUID identity = new java.util.UUID(buf.readLong(), buf.readLong());
        int version = buf.readVarInt();
        return new LODStreamSessionPayload(dimension, streamEpoch, manifestRequestId, manifestRequired, identity, version);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
