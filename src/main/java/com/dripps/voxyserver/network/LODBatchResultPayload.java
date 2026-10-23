package com.dripps.voxyserver.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record LODBatchResultPayload(long streamEpoch, long batchId, int status) implements CustomPacketPayload {
    public static final int SUCCESS = 0;
    public static final int FAILURE = 1;
    public static final int OVERFLOW = 2;
    public static final int STALE = 3;

    public static final Type<LODBatchResultPayload> TYPE =
            new Type<>(Identifier.parse("voxyserver:lod_batch_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, LODBatchResultPayload> CODEC =
            StreamCodec.of(LODBatchResultPayload::write, LODBatchResultPayload::read);

    public LODBatchResultPayload {
        validate(streamEpoch, batchId, status);
    }

    private static void write(RegistryFriendlyByteBuf buf, LODBatchResultPayload payload) {
        validate(payload.streamEpoch, payload.batchId, payload.status);
        buf.writeVarLong(payload.streamEpoch);
        buf.writeVarLong(payload.batchId);
        buf.writeByte(payload.status);
    }

    private static LODBatchResultPayload read(RegistryFriendlyByteBuf buf) {
        long streamEpoch = buf.readVarLong();
        long batchId = buf.readVarLong();
        int status = buf.readByte() & 0xFF;
        validate(streamEpoch, batchId, status);
        return new LODBatchResultPayload(streamEpoch, batchId, status);
    }

    private static void validate(long streamEpoch, long batchId, int status) {
        if (streamEpoch < 0L || batchId < 0L || status < SUCCESS || status > STALE) {
            throw new IllegalArgumentException("invalid LOD batch result: epoch=" + streamEpoch
                    + ", batchId=" + batchId + ", status=" + status);
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
