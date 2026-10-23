package com.dripps.voxyserver.util;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.stream.Collectors;

/** Resolves runtime IDs to their meaning on either side of the connection. Worker-thread only. */
public final class SemanticPalette {
    private record Entry(String state, String biome, int light) {}
    private static final BoundedLruCache<BlockState, String> STATE_NAMES = new BoundedLruCache<>(4096);
    private static final BoundedLruCache<Entry, Long> TOKENS = new BoundedLruCache<>(16_384);
    private SemanticPalette() {}

    public static long hashStored(long[] data, me.cortex.voxy.common.world.other.Mapper mapper,
                                  Registry<Biome> registry) {
        var tokens = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
        return SemanticSectionHash.computeMapped(data, mappingId -> {
            long token = tokens.get(mappingId);
            if (token == 0 && !tokens.containsKey(mappingId)) {
                token = resolve(new int[]{IdRemapper.toVanillaBlockStateId(mapper, mappingId)},
                        new int[]{IdRemapper.toVanillaBiomeIdFromMapper(mapper, mappingId, registry)},
                        new byte[]{(byte) IdRemapper.getLightFromMapping(mappingId)}, registry)[0];
                tokens.put(mappingId, token);
            }
            return token;
        });
    }

    public static long[] resolve(int[] states, int[] biomes, byte[] lights, Registry<Biome> registry) {
        if (states.length != biomes.length || states.length != lights.length) {
            throw new IllegalArgumentException("inconsistent semantic palette");
        }
        long[] palette = new long[states.length];
        for (int i = 0; i < states.length; i++) {
            BlockState state = Block.BLOCK_STATE_REGISTRY.byId(states[i]);
            if (state == null) {
                throw new IllegalArgumentException("unknown semantic registry entry");
            }
            Entry entry;
            if (state.isAir()) {
                // Voxy discards biome and air variants when composing a mapping ID.
                // Mapper biome slot zero therefore has no meaning for an air voxel.
                entry = new Entry("minecraft:air[]", "", lights[i] & 255);
            } else {
                Biome biome = registry.get(biomes[i]).orElseThrow(
                        () -> new IllegalArgumentException("unknown biome registry ID")).value();
                if (registry.getKey(biome) == null) {
                    throw new IllegalArgumentException("unknown semantic registry entry");
                }
                entry = new Entry(blockStateName(state), registry.getKey(biome).toString(), lights[i] & 255);
            }
            Long token = TOKENS.get(entry);
            if (token == null) {
                token = SemanticSectionHash.paletteEntry(entry.state(), entry.biome(), entry.light());
                TOKENS.put(entry, token);
            }
            palette[i] = token;
        }
        return palette;
    }

    public static String blockStateName(BlockState state) {
        String cached = STATE_NAMES.get(state);
        if (cached != null) return cached;
        Map<String, String> properties = state.getValues()
                .collect(Collectors.toMap(entry -> entry.property().getName(), entry -> entry.valueName()));
        String name = SemanticSectionHash.canonicalBlockState(
                BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), properties);
        STATE_NAMES.put(state, name);
        return name;
    }

}
