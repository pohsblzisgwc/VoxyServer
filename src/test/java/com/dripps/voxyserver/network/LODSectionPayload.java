package com.dripps.voxyserver.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

// One native LOD 0..4 section sent from server to client
// contains: dimension id, section position key, a LUT of vanilla registry ids, and an index array
// contentHash is the canonical content fingerprint the client persists for hash sync
public record LODSectionPayload(
        Identifier dimension,
        long sectionKey,
        int[] lutBlockStateIds,
        int[] lutBiomeIds,
        byte[] lutLight,
        short[] indexArray,
        long contentHash,
        byte childMask
) implements CustomPacketPayload {

    public static final Type<LODSectionPayload> TYPE =
            new Type<>(Identifier.parse("voxyserver:lod_section"));

    public static final StreamCodec<RegistryFriendlyByteBuf, LODSectionPayload> CODEC =
            StreamCodec.of(LODSectionPayload::write, LODSectionPayload::read);

    public LODSectionPayload(Identifier dimension,long key,int[] states,int[] biomes,byte[] light,short[] indices,long hash) {
        this(dimension,key,states,biomes,light,indices,hash,(byte)0);
    }

    public LODSectionPayload {
        if (dimension == null) {
            throw new IllegalArgumentException("dimension cannot be null");
        }
        if (lutBlockStateIds == null || lutBiomeIds == null || lutLight == null || indexArray == null) {
            throw new IllegalArgumentException("section array components cannot be null");
        }
        int lutLen = lutBlockStateIds.length;
        if (lutLen < 1 || lutLen > LODBulkPayload.MAX_LUT_ENTRIES
                || lutBiomeIds.length != lutLen || lutLight.length != lutLen
                || indexArray.length < 1
                || indexArray.length > LODBulkPayload.MAX_INDEX_ENTRIES) {
            throw new IllegalArgumentException("invalid section data array sizes");
        }
    }

    private static void write(RegistryFriendlyByteBuf buf, LODSectionPayload payload) {
        int lutLen = payload.lutBlockStateIds.length;
        buf.writeIdentifier(payload.dimension);
        buf.writeLong(payload.sectionKey);
        buf.writeLong(payload.contentHash);
        buf.writeByte(payload.childMask);

        buf.writeVarInt(lutLen);
        for (int i = 0; i < lutLen; i++) {
            buf.writeVarInt(payload.lutBlockStateIds[i]);
            buf.writeVarInt(payload.lutBiomeIds[i]);
            buf.writeByte(payload.lutLight[i]);
        }

        // pack indices at minimum bit width for lut size
        int bitsPerEntry = Math.max(1, 32 - Integer.numberOfLeadingZeros(Math.max(lutLen - 1, 0)));
        int entriesPerLong = 64 / bitsPerEntry;
        int longCount = (payload.indexArray.length + entriesPerLong - 1) / entriesPerLong;

        buf.writeVarInt(payload.indexArray.length);
        buf.writeByte(bitsPerEntry);
        for (int li = 0; li < longCount; li++) {
            long packed = 0L;
            int base = li * entriesPerLong;
            for (int ei = 0; ei < entriesPerLong && base + ei < payload.indexArray.length; ei++) {
                packed |= ((long) (payload.indexArray[base + ei] & 0xFFFF)) << (ei * bitsPerEntry);
            }
            buf.writeLong(packed);
        }
    }

    private static LODSectionPayload read(RegistryFriendlyByteBuf buf) {
        Identifier dimension = buf.readIdentifier();
        long sectionKey = buf.readLong();
        long contentHash = buf.readLong();
        byte children = buf.readByte();

        int lutLen = buf.readVarInt();
        if (lutLen < 1 || lutLen > LODBulkPayload.MAX_LUT_ENTRIES) {
            throw new IllegalArgumentException("invalid lookup table size: " + lutLen);
        }
        if ((long) lutLen * 3L > buf.readableBytes()) {
            throw new IllegalArgumentException("truncated LUT in section payload: needed " + (lutLen * 3) + " bytes, but only " + buf.readableBytes() + " available");
        }
        int[] blockStateIds = new int[lutLen];
        int[] biomeIds = new int[lutLen];
        byte[] light = new byte[lutLen];
        for (int i = 0; i < lutLen; i++) {
            blockStateIds[i] = buf.readVarInt();
            biomeIds[i] = buf.readVarInt();
            light[i] = buf.readByte();
        }

        int indexLen = buf.readVarInt();
        int bitsPerEntry = buf.readByte() & 0xFF;
        if (indexLen < 1 || indexLen > LODBulkPayload.MAX_INDEX_ENTRIES
                || bitsPerEntry < 1 || bitsPerEntry > 16) {
            throw new IllegalArgumentException("invalid packed lod index data");
        }
        int entriesPerLong = 64 / bitsPerEntry;
        int longCount = (indexLen + entriesPerLong - 1) / entriesPerLong;
        if ((long) longCount * 8L > buf.readableBytes()) {
            throw new IllegalArgumentException("truncated index data in section payload: needed " + (longCount * 8) + " bytes, but only " + buf.readableBytes() + " available");
        }
        long mask = (1L << bitsPerEntry) - 1;
        short[] indexArray = new short[indexLen];
        int idx = 0;
        while (idx < indexLen) {
            long packed = buf.readLong();
            for (int ei = 0; ei < entriesPerLong && idx < indexLen; ei++, idx++) {
                indexArray[idx] = (short) ((packed >> (ei * bitsPerEntry)) & mask);
            }
        }

        return new LODSectionPayload(dimension, sectionKey, blockStateIds, biomeIds, light, indexArray, contentHash, children);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
