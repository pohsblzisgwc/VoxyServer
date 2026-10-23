package com.dripps.voxyserver.sync;

import com.dripps.voxyserver.network.LODSectionPayload;
import com.dripps.voxyserver.util.*;

import it.unimi.dsi.fastutil.longs.Long2ShortOpenHashMap;

import me.cortex.voxy.common.world.other.Mapper;

import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.biome.Biome;

public final class NativeNodeCodec {
    private NativeNodeCodec() {}

    public static long proofHash(long dataHash, byte children) {
        long h = dataHash ^ ((children & 255L) * 0x9e3779b97f4a7c15L);
        h = (h ^ (h >>> 30)) * 0xbf58476d1ce4e5b9L;
        h = (h ^ (h >>> 27)) * 0x94d049bb133111ebL;
        h ^= h >>> 31;
        return h == 0 ? 1 : h;
    }

    public static LODSectionPayload encode(
            Identifier dimension,
            long key,
            long[] data,
            byte children,
            Mapper mapper,
            Registry<Biome> biomes) {
        var lut = new Long2ShortOpenHashMap();
        lut.defaultReturnValue((short) -1);
        short[] indices = new short[data.length];
        int count = 0;
        for (int i = 0; i < data.length; i++) {
            short slot = lut.putIfAbsent(data[i], (short) count);
            if (slot == -1) slot = (short) count++;
            indices[i] = slot;
        }
        int[] states = new int[count], biomeIds = new int[count];
        byte[] light = new byte[count];
        for (var e : lut.long2ShortEntrySet()) {
            int i = e.getShortValue() & 65535;
            long mapping = e.getLongKey();
            states[i] = IdRemapper.toVanillaBlockStateId(mapper, mapping);
            biomeIds[i] = IdRemapper.toVanillaBiomeIdFromMapper(mapper, mapping, biomes);
            light[i] = (byte) Mapper.getLightId(mapping);
        }
        long hash =
                SemanticSectionHash.compute(
                        SemanticPalette.resolve(states, biomeIds, light, biomes), indices);
        return new LODSectionPayload(
                dimension,
                key,
                states,
                biomeIds,
                light,
                indices,
                proofHash(hash, children),
                children);
    }
}
