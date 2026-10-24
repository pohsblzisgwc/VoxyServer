package com.dripps.voxyserver.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Content identity, independent of palettes, mapper IDs, registry order and wire encoding. */
public final class SemanticSectionHash {
    public static final int VERSION = 1;
    public static final int VOXELS = 32 * 32 * 32;
    private static final long SEED = 0x53454D414E544943L;

    private SemanticSectionHash() {}

    public static String canonicalBlockState(String blockName, java.util.Map<String, String> properties) {
        if (blockName == null || properties == null) throw new IllegalArgumentException("invalid block state");
        StringBuilder result = new StringBuilder(blockName).append('[');
        boolean first = true;
        for (var entry : new java.util.TreeMap<>(properties).entrySet()) {
            if (!first) result.append(',');
            result.append(entry.getKey()).append('=').append(entry.getValue());
            first = false;
        }
        return result.append(']').toString();
    }

    public static long paletteEntry(String blockState, String biome, int light) {
        if (blockState == null || biome == null || light < 0 || light > 255) {
            throw new IllegalArgumentException("invalid semantic palette entry");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((byte) VERSION);
            putString(digest, blockState);
            putString(digest, biome);
            digest.update((byte) light);
            byte[] bytes = digest.digest();
            long value = 0;
            for (int i = 0; i < Long.BYTES; i++) value = (value << 8) | (bytes[i] & 255L);
            return value;
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    public static long compute(long[] semanticPalette, short[] indices) {
        if (semanticPalette.length == 0 || indices.length != VOXELS) {
            throw new IllegalArgumentException("expected a complete 32 cubed section");
        }
        long hash = SEED ^ VERSION;
        for (short index : indices) {
            int slot = index & 65535;
            if (slot >= semanticPalette.length) throw new IllegalArgumentException("invalid palette index");
            hash ^= semanticPalette[slot];
            hash = Long.rotateLeft(hash, 27) * 0x9E3779B97F4A7C15L + 0x165667B19E3779F9L;
        }
        hash ^= VOXELS;
        hash = avalanche(hash);
        return hash == 0 ? 1 : hash; // zero means an unknown/legacy fingerprint
    }

    /** Hash actual stored voxels after resolving local mapper IDs to the same semantic tokens. */
    public static long computeMapped(long[] voxels, java.util.function.LongUnaryOperator resolver) {
        if (voxels.length != VOXELS) throw new IllegalArgumentException("expected a complete section");
        long hash = SEED ^ VERSION;
        for (long voxel : voxels) {
            hash ^= resolver.applyAsLong(voxel);
            hash = Long.rotateLeft(hash, 27) * 0x9E3779B97F4A7C15L + 0x165667B19E3779F9L;
        }
        hash = avalanche(hash ^ VOXELS);
        return hash == 0 ? 1 : hash;
    }

    private static void putString(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        for (int shift = 24; shift >= 0; shift -= 8) digest.update((byte) (bytes.length >>> shift));
        digest.update(bytes);
    }

    private static long avalanche(long value) {
        value = (value ^ (value >>> 33)) * 0xff51afd7ed558ccdL;
        value = (value ^ (value >>> 33)) * 0xc4ceb9fe1a85ec53L;
        return value ^ (value >>> 33);
    }
}
