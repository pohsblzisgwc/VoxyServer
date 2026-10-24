package com.dripps.voxyserver.network;

import me.cortex.voxy.common.world.WorldEngine;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record VersionSelectPayload(
        long epoch, long round, int page, int pages, long[] keys, int[] versions)
        implements CustomPacketPayload {
    public static final int MAX_PAGES = 65536;
    public static final Type<VersionSelectPayload> TYPE =
            new Type<>(Identifier.parse("voxyserver:version_select_v6"));
    public static final StreamCodec<RegistryFriendlyByteBuf, VersionSelectPayload> CODEC =
            StreamCodec.of(VersionSelectPayload::write, VersionSelectPayload::read);

    public VersionSelectPayload {
        if (epoch <= 0
                || round <= 0
                || pages < 1
                || pages > MAX_PAGES
                || page < 0
                || page >= pages
                || keys == null
                || versions == null
                || keys.length > 256
                || keys.length != versions.length
                || (keys.length == 0 && (pages != 1 || page != 0)))
            throw new IllegalArgumentException("selection bounds");
        var seen = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        for (int i = 0; i < keys.length; i++)
            if ((keys[i] & 15) != 0
                    || WorldEngine.getLevel(keys[i]) != 0
                    || versions[i] < 1
                    || !seen.add(keys[i]))
                throw new IllegalArgumentException("invalid LOD0 selection");
        keys = keys.clone();
        versions = versions.clone();
    }

    private static void write(RegistryFriendlyByteBuf b, VersionSelectPayload p) {
        b.writeVarLong(p.epoch);
        b.writeVarLong(p.round);
        b.writeVarInt(p.page);
        b.writeVarInt(p.pages);
        b.writeVarInt(p.keys.length);
        for (int i = 0; i < p.keys.length; i++) {
            b.writeLong(p.keys[i]);
            b.writeInt(p.versions[i]);
        }
    }

    private static VersionSelectPayload read(RegistryFriendlyByteBuf b) {
        long e = b.readVarLong(), r = b.readVarLong();
        int p = b.readVarInt(), n = b.readVarInt(), l = b.readVarInt();
        if (l < 0 || l > 256 || b.readableBytes() < l * 12)
            throw new IllegalArgumentException("selection length");
        long[] k = new long[l];
        int[] v = new int[l];
        for (int i = 0; i < l; i++) {
            k[i] = b.readLong();
            v[i] = b.readInt();
        }
        return new VersionSelectPayload(e, r, p, n, k, v);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
