package com.dripps.voxyserver.network;

import com.dripps.voxyserver.util.IdRemapper;

import io.netty.buffer.Unpooled;

import it.unimi.dsi.fastutil.longs.Long2ShortOpenHashMap;

import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.other.Mapper;

import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.level.biome.Biome;

import java.util.*;

/** Protocol 6 carries a 32-bit server version, never a voxel-content hash. */
public record VersionedNodePayload(
        long key,
        int version,
        int[] states,
        int[] biomes,
        byte[] light,
        short[] indices,
        byte children) {
    public VersionedNodePayload {
        if ((key & 15) != 0
                || WorldEngine.getLevel(key) != 0
                || version < 1
                || states == null
                || biomes == null
                || light == null
                || indices == null
                || states.length < 1
                || states.length > 32768
                || biomes.length != states.length
                || light.length != states.length
                || indices.length != 32768)
            throw new IllegalArgumentException("invalid versioned LOD0 node");
        for (int i = 0; i < states.length; i++)
            if (states[i] < 0 || biomes[i] < 0)
                throw new IllegalArgumentException("negative palette id");
        for (short index : indices)
            if ((index & 65535) >= states.length)
                throw new IllegalArgumentException("invalid palette index");
    }

    public static VersionedNodePayload encode(
            long key,
            int version,
            long[] data,
            byte children,
            Mapper mapper,
            Registry<Biome> biomes) {
        var lut = new Long2ShortOpenHashMap();
        lut.defaultReturnValue((short) -1);
        short[] indices = new short[32768];
        int count = 0;
        for (int i = 0; i < data.length; i++) {
            short slot = lut.putIfAbsent(data[i], (short) count);
            if (slot == -1) slot = (short) count++;
            indices[i] = slot;
        }
        int[] states = new int[count], bids = new int[count];
        byte[] light = new byte[count];
        for (var entry : lut.long2ShortEntrySet()) {
            int i = entry.getShortValue() & 65535;
            long value = entry.getLongKey();
            states[i] = IdRemapper.toVanillaBlockStateId(mapper, value);
            bids[i] = IdRemapper.toVanillaBiomeIdFromMapper(mapper, value, biomes);
            light[i] = (byte) Mapper.getLightId(value);
        }
        return new VersionedNodePayload(key, version, states, bids, light, indices, children);
    }

    public static byte[] encodeBatch(List<VersionedNodePayload> nodes, RegistryAccess registry) {
        if (nodes.isEmpty() || nodes.size() > 16)
            throw new IllegalArgumentException("node batch count");
        var raw = Unpooled.buffer(256, PreSerializedLodPayload.MAX_ENCODED_BYTES);
        try {
            var b = new RegistryFriendlyByteBuf(raw, registry);
            b.writeVarInt(nodes.size());
            for (var p : nodes) {
                b.writeLong(p.key);
                b.writeInt(p.version);
                b.writeByte(p.children);
                b.writeVarInt(p.states.length);
                for (int i = 0; i < p.states.length; i++) {
                    b.writeVarInt(p.states[i]);
                    b.writeVarInt(p.biomes[i]);
                    b.writeByte(p.light[i]);
                }
                int bits = Math.max(1, 32 - Integer.numberOfLeadingZeros(p.states.length - 1)),
                        per = 64 / bits;
                b.writeByte(bits);
                for (int base = 0; base < 32768; base += per) {
                    long packed = 0;
                    for (int i = 0; i < per && base + i < 32768; i++)
                        packed |= (long) (p.indices[base + i] & 65535) << (i * bits);
                    b.writeLong(packed);
                }
            }
            byte[] out = new byte[b.readableBytes()];
            b.readBytes(out);
            return out;
        } finally {
            raw.release();
        }
    }

    public static List<VersionedNodePayload> decodeBatch(byte[] data, RegistryAccess registry) {
        var raw = Unpooled.wrappedBuffer(data);
        try {
            var b = new RegistryFriendlyByteBuf(raw, registry);
            int count = b.readVarInt();
            if (count < 1 || count > 16) throw new IllegalArgumentException("node count");
            var nodes = new ArrayList<VersionedNodePayload>();
            for (int n = 0; n < count; n++) {
                long key = b.readLong();
                int version = b.readInt();
                byte child = b.readByte();
                int l = b.readVarInt();
                if (l < 1 || l > 32768 || (long) l * 3 > b.readableBytes())
                    throw new IllegalArgumentException("palette size");
                int[] states = new int[l], biomes = new int[l];
                byte[] light = new byte[l];
                for (int i = 0; i < l; i++) {
                    states[i] = b.readVarInt();
                    biomes[i] = b.readVarInt();
                    light[i] = b.readByte();
                }
                int bits = b.readUnsignedByte();
                if (bits != Math.max(1, 32 - Integer.numberOfLeadingZeros(l - 1)))
                    throw new IllegalArgumentException("packed bit width");
                int per = 64 / bits, longs = (32768 + per - 1) / per;
                if ((long) longs * 8 > b.readableBytes())
                    throw new IllegalArgumentException("truncated node");
                short[] indices = new short[32768];
                long mask = (1L << bits) - 1;
                int i = 0;
                for (int j = 0; j < longs; j++) {
                    long packed = b.readLong();
                    for (int k = 0; k < per && i < 32768; k++)
                        indices[i++] = (short) ((packed >>> (k * bits)) & mask);
                }
                nodes.add(
                        new VersionedNodePayload(
                                key, version, states, biomes, light, indices, child));
            }
            if (b.readableBytes() != 0) throw new IllegalArgumentException("trailing node data");
            return List.copyOf(nodes);
        } finally {
            raw.release();
        }
    }
}
