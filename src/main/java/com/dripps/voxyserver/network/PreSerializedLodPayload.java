package com.dripps.voxyserver.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

// Wraps versioned LOD0 batch bytes so the heavy encoding
// happens on the streaming thread instead of the tick thread
public record PreSerializedLodPayload(
        long streamEpoch,
        long batchId,
        int sectionCount,
        byte[] data,
        long demandGroup,
        long demandRequestId)
        implements CustomPacketPayload {

    public static final int MAX_ENCODED_BYTES = 8 * 1024 * 1024;
    public static final int MAX_SECTIONS = 16;

    public static final Type<PreSerializedLodPayload> TYPE =
            new Type<>(Identifier.parse("voxyserver:lod_preserialized"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PreSerializedLodPayload> CODEC =
            StreamCodec.of(PreSerializedLodPayload::write, PreSerializedLodPayload::read);

    public PreSerializedLodPayload(long epoch, long batch, int count, byte[] data) {
        this(epoch, batch, count, data, 0, 0);
    }

    public PreSerializedLodPayload {
        if (demandRequestId < 0
                || (demandRequestId != 0
                        && ((demandGroup & 15) != 0
                                || me.cortex.voxy.common.world.WorldEngine.getLevel(demandGroup)
                                        > 4)))
            throw new IllegalArgumentException("invalid demand delivery");
        if (data == null) {
            throw new IllegalArgumentException("data cannot be null");
        }
        validate(streamEpoch, batchId, sectionCount, data.length);
    }

    private static void write(RegistryFriendlyByteBuf buf, PreSerializedLodPayload payload) {
        validate(payload.streamEpoch, payload.batchId, payload.sectionCount, payload.data.length);
        buf.writeVarLong(payload.streamEpoch);
        buf.writeVarLong(payload.batchId);
        buf.writeLong(payload.demandGroup);
        buf.writeVarLong(payload.demandRequestId);
        buf.writeVarInt(payload.sectionCount);
        buf.writeVarInt(payload.data.length);
        buf.writeBytes(payload.data);
    }

    private static PreSerializedLodPayload read(RegistryFriendlyByteBuf buf) {
        long streamEpoch = buf.readVarLong();
        long batchId = buf.readVarLong();
        long demandGroup = buf.readLong();
        long demandRequestId = buf.readVarLong();
        int sectionCount = buf.readVarInt();
        int len = buf.readVarInt();
        validate(streamEpoch, batchId, sectionCount, len);
        if (len > buf.readableBytes()) {
            throw new IllegalArgumentException("shortened preserialized payload");
        }
        byte[] data = new byte[len];
        buf.readBytes(data);
        return new PreSerializedLodPayload(
                streamEpoch, batchId, sectionCount, data, demandGroup, demandRequestId);
    }

    public PreSerializedLodPayload withDelivery(long streamEpoch, long batchId) {
        validate(streamEpoch, batchId, this.sectionCount, this.data.length);
        return new PreSerializedLodPayload(
                streamEpoch, batchId, this.sectionCount, this.data, demandGroup, demandRequestId);
    }

    public PreSerializedLodPayload withDemand(long group, long request) {
        return new PreSerializedLodPayload(
                streamEpoch, batchId, sectionCount, data, group, request);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void validate(long streamEpoch, long batchId, int sectionCount, int dataLength) {
        if (streamEpoch < 0L || batchId < 0L) {
            throw new IllegalArgumentException("invalid delivery identity");
        }
        if (sectionCount < 1 || sectionCount > MAX_SECTIONS) {
            throw new IllegalArgumentException("invalid section count: " + sectionCount);
        }
        if (dataLength < 1 || dataLength > MAX_ENCODED_BYTES) {
            throw new IllegalArgumentException("invalid preserialized payload size: " + dataLength);
        }
    }
}
