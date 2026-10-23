package com.dripps.voxyserver.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record LODRegionSyncPayload(
        long[] regionKeys,
        long[] regionHashes
) implements CustomPacketPayload {

    public static final int MAX_REGIONS = 4096;

    public static final Type<LODRegionSyncPayload> TYPE =
            new Type<>(Identifier.parse("voxyserver:lod_region_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, LODRegionSyncPayload> CODEC =
            StreamCodec.of(LODRegionSyncPayload::write, LODRegionSyncPayload::read);

    public LODRegionSyncPayload {
        if (regionKeys == null || regionHashes == null) {
            throw new IllegalArgumentException("regionKeys and regionHashes cannot be null");
        }
        if (regionKeys.length != regionHashes.length) {
            throw new IllegalArgumentException("Keys and hashes length mismatch: "
                    + regionKeys.length + " vs " + regionHashes.length);
        }
        if (regionKeys.length > MAX_REGIONS) {
            throw new IllegalArgumentException("Too many region hashes: " + regionKeys.length
                    + " exceeds limit of " + MAX_REGIONS);
        }
    }

    private static void write(RegistryFriendlyByteBuf buf, LODRegionSyncPayload payload) {
        int len = payload.regionKeys.length;
        buf.writeVarInt(len);
        for (int i = 0; i < len; i++) {
            buf.writeLong(payload.regionKeys[i]);
            buf.writeLong(payload.regionHashes[i]);
        }
    }

    private static LODRegionSyncPayload read(RegistryFriendlyByteBuf buf) {
        int len = buf.readVarInt();
        if (len < 0 || len > MAX_REGIONS) {
            throw new IllegalArgumentException("Invalid region sync length: " + len
                    + ", must be between 0 and " + MAX_REGIONS);
        }
        long requiredBytes = (long) len * 16L;
        if ((long) buf.readableBytes() < requiredBytes) {
            throw new IllegalArgumentException("Buffer underrun in LODRegionSyncPayload: needed "
                    + requiredBytes + " bytes, but only " + buf.readableBytes() + " bytes available");
        }
        long[] keys = new long[len];
        long[] hashes = new long[len];
        for (int i = 0; i < len; i++) {
            keys[i] = buf.readLong();
            hashes[i] = buf.readLong();
        }
        return new LODRegionSyncPayload(keys, hashes);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
