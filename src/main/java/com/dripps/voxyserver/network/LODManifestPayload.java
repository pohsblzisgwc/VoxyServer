package com.dripps.voxyserver.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

// client to server manifest of sections it already stores for a dimension
public record LODManifestPayload(
        Identifier dimension,
        long streamEpoch,
        long requestId,
        long[] keys,
        long[] hashes,
        int status,
        long pageId
) implements CustomPacketPayload {

    public static final int MAX_ENTRIES = 128;
    public static final int PART = 0;
    public static final int COMPLETE = 1;
    public static final int FAILED = 2;
    public static final int CANCELED = 3;

    public static final Type<LODManifestPayload> TYPE =
            new Type<>(Identifier.parse("voxyserver:lod_manifest_v3"));

    public static final StreamCodec<RegistryFriendlyByteBuf, LODManifestPayload> CODEC =
            StreamCodec.of(LODManifestPayload::write, LODManifestPayload::read);

    public LODManifestPayload(Identifier dimension, long streamEpoch, long requestId, long[] keys, long[] hashes, int status) {
        this(dimension, streamEpoch, requestId, keys, hashes, status, 0L);
    }

    public LODManifestPayload {
        if (pageId < 0) throw new IllegalArgumentException("invalid manifest page ID");
        if (dimension == null) {
            throw new IllegalArgumentException("dimension cannot be null");
        }
        if (keys == null || hashes == null) {
            throw new IllegalArgumentException("keys and hashes cannot be null");
        }
        if (keys.length != hashes.length) {
            throw new IllegalArgumentException("keys and hashes length mismatch: " + keys.length + " vs " + hashes.length);
        }
        if (keys.length > MAX_ENTRIES) {
            throw new IllegalArgumentException("keys length exceeds maximum: " + keys.length + " > " + MAX_ENTRIES);
        }
        if (streamEpoch < 0L || requestId < 0L || status < PART || status > CANCELED) {
            throw new IllegalArgumentException("invalid manifest metadata: streamEpoch=" + streamEpoch
                    + ", requestId=" + requestId + ", status=" + status);
        }
    }

    private static void write(RegistryFriendlyByteBuf buf, LODManifestPayload payload) {
        buf.writeIdentifier(payload.dimension);
        buf.writeVarLong(payload.streamEpoch);
        buf.writeVarLong(payload.requestId);
        buf.writeVarLong(payload.pageId);
        buf.writeByte(payload.status);
        int count = payload.keys.length;
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            buf.writeLong(payload.keys[i]);
            buf.writeLong(payload.hashes[i]);
        }
    }

    private static LODManifestPayload read(RegistryFriendlyByteBuf buf) {
        Identifier dimension = buf.readIdentifier();
        long streamEpoch = buf.readVarLong();
        long requestId = buf.readVarLong();
        long pageId = buf.readVarLong();
        int status = buf.readByte() & 0xFF;
        int count = buf.readVarInt();
        if (streamEpoch < 0L || requestId < 0L || status < PART || status > CANCELED
                || count < 0 || count > MAX_ENTRIES) {
            throw new IllegalArgumentException("invalid manifest payload size: " + count);
        }
        long requiredBytes = (long) count * 16L;
        if ((long) buf.readableBytes() < requiredBytes) {
            throw new IllegalArgumentException("Manifest payload truncated: declared " + count
                    + " entries (" + requiredBytes + " bytes), but only " + buf.readableBytes() + " bytes available");
        }
        long[] keys = new long[count];
        long[] hashes = new long[count];
        for (int i = 0; i < count; i++) {
            keys[i] = buf.readLong();
            hashes[i] = buf.readLong();
        }
        return new LODManifestPayload(dimension, streamEpoch, requestId, keys, hashes, status, pageId);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
