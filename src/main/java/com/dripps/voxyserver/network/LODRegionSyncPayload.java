package com.dripps.voxyserver.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-server payload reporting cached Region hashes to bypass streaming already-synced areas.
 */
public record LODRegionSyncPayload(
        Identifier dimension,
        long[] regionKeys,
        long[] regionHashes
) implements CustomPacketPayload {

    public static final Type<LODRegionSyncPayload> TYPE =
            new Type<>(Identifier.parse("voxyserver:lod_region_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, LODRegionSyncPayload> CODEC =
            StreamCodec.of(LODRegionSyncPayload::write, LODRegionSyncPayload::read);

    private static void write(RegistryFriendlyByteBuf buf, LODRegionSyncPayload payload) {
        buf.writeIdentifier(payload.dimension);
        int len = payload.regionKeys.length;
        buf.writeVarInt(len);
        for (int i = 0; i < len; i++) {
            buf.writeLong(payload.regionKeys[i]);
            buf.writeLong(payload.regionHashes[i]);
        }
    }

    private static LODRegionSyncPayload read(RegistryFriendlyByteBuf buf) {
        Identifier dim = buf.readIdentifier();
        int len = buf.readVarInt();
        long[] keys = new long[len];
        long[] hashes = new long[len];
        for (int i = 0; i < len; i++) {
            keys[i] = buf.readLong();
            hashes[i] = buf.readLong();
        }
        return new LODRegionSyncPayload(dim, keys, hashes);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
