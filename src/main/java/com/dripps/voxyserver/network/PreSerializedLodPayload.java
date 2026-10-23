package com.dripps.voxyserver.network;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

// wraps preserialized LODBulkPayload bytes so the heavy encoding
// happens on the streaming thread instead of the tick thread
public record PreSerializedLodPayload(long streamEpoch, long batchId, int sectionCount,
                                      byte[] data) implements CustomPacketPayload {

    public static final int MAX_ENCODED_BYTES = 8 * 1024 * 1024;
    public static final int MAX_SECTIONS = 16;

    public static final Type<PreSerializedLodPayload> TYPE =
            new Type<>(Identifier.parse("voxyserver:lod_preserialized"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PreSerializedLodPayload> CODEC =
            StreamCodec.of(PreSerializedLodPayload::write, PreSerializedLodPayload::read);

    public PreSerializedLodPayload {
        if (data == null) {
            throw new IllegalArgumentException("data cannot be null");
        }
        validate(streamEpoch, batchId, sectionCount, data.length);
    }

    private static void write(RegistryFriendlyByteBuf buf, PreSerializedLodPayload payload) {
        validate(payload.streamEpoch, payload.batchId, payload.sectionCount, payload.data.length);
        buf.writeVarLong(payload.streamEpoch);
        buf.writeVarLong(payload.batchId);
        buf.writeVarInt(payload.sectionCount);
        buf.writeVarInt(payload.data.length);
        buf.writeBytes(payload.data);
    }

    private static PreSerializedLodPayload read(RegistryFriendlyByteBuf buf) {
        long streamEpoch = buf.readVarLong();
        long batchId = buf.readVarLong();
        int sectionCount = buf.readVarInt();
        int len = buf.readVarInt();
        validate(streamEpoch, batchId, sectionCount, len);
        if (len > buf.readableBytes()) {
            throw new IllegalArgumentException("shortened preserialized payload");
        }
        byte[] data = new byte[len];
        buf.readBytes(data);
        return new PreSerializedLodPayload(streamEpoch, batchId, sectionCount, data);
    }

    // encodes an LODBulkPayload to preserialized bytes on the calling thread
    public static PreSerializedLodPayload fromBulk(LODBulkPayload bulk, RegistryAccess registryAccess) {
        var raw = Unpooled.buffer(256, MAX_ENCODED_BYTES);
        try {
            var rfb = new RegistryFriendlyByteBuf(raw, registryAccess);
            LODBulkPayload.CODEC.encode(rfb, bulk);
            byte[] bytes = new byte[rfb.readableBytes()];
            rfb.readBytes(bytes);
            if (bytes.length > MAX_ENCODED_BYTES) {
                throw new IllegalArgumentException("preserialized payload too large: " + bytes.length);
            }
            return new PreSerializedLodPayload(0L, 0L, bulk.sections().size(), bytes);
        } finally {
            raw.release();
        }
    }

    public PreSerializedLodPayload withDelivery(long streamEpoch, long batchId) {
        validate(streamEpoch, batchId, this.sectionCount, this.data.length);
        return new PreSerializedLodPayload(streamEpoch, batchId, this.sectionCount, this.data);
    }

    public LODBulkPayload decodeBulk(RegistryAccess registryAccess) {
        var raw = Unpooled.wrappedBuffer(data);
        try {
            var rfb = new RegistryFriendlyByteBuf(raw, registryAccess);
            LODBulkPayload decoded = LODBulkPayload.CODEC.decode(rfb);
            if (rfb.readableBytes() != 0) {
                throw new IllegalArgumentException("trailing preserialized data");
            }
            return decoded;
        } finally {
            raw.release();
        }
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
