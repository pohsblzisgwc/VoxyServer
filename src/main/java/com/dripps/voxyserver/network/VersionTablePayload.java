package com.dripps.voxyserver.network;

import com.dripps.voxyserver.sync.version.VersionScope;

import me.cortex.voxy.common.world.WorldEngine;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record VersionTablePayload(
        long epoch,
        long round,
        long root,
        long digest,
        int page,
        int pages,
        long[] keys,
        int[] versions)
        implements CustomPacketPayload {
    public static final Type<VersionTablePayload> TYPE =
            new Type<>(Identifier.parse("voxyserver:version_table_v6"));
    public static final StreamCodec<RegistryFriendlyByteBuf, VersionTablePayload> CODEC =
            StreamCodec.of(VersionTablePayload::write, VersionTablePayload::read);

    public VersionTablePayload {
        if (epoch <= 0
                || round <= 0
                || (root & 15) != 0
                || WorldEngine.getLevel(root) != 4
                || pages < 1
                || pages > 16
                || page < 0
                || page >= pages
                || keys == null
                || versions == null
                || keys.length > 256
                || keys.length != versions.length
                || (keys.length == 0 && pages != 1))
            throw new IllegalArgumentException("table bounds");
        int previous = -1;
        for (int i = 0; i < keys.length; i++) {
            int slot = VersionScope.slot(keys[i]);
            if ((keys[i] & 15) != 0
                    || WorldEngine.getLevel(keys[i]) != 0
                    || VersionScope.root(keys[i]) != root
                    || versions[i] < 1
                    || slot <= previous)
                throw new IllegalArgumentException("invalid version table");
            previous = slot;
        }
        keys = keys.clone();
        versions = versions.clone();
    }

    private static void write(RegistryFriendlyByteBuf b, VersionTablePayload p) {
        b.writeVarLong(p.epoch);
        b.writeVarLong(p.round);
        b.writeLong(p.root);
        b.writeLong(p.digest);
        b.writeVarInt(p.page);
        b.writeVarInt(p.pages);
        b.writeVarInt(p.keys.length);
        for (int i = 0; i < p.keys.length; i++) {
            b.writeLong(p.keys[i]);
            b.writeInt(p.versions[i]);
        }
    }

    private static VersionTablePayload read(RegistryFriendlyByteBuf b) {
        long e = b.readVarLong(), r = b.readVarLong(), root = b.readLong(), h = b.readLong();
        int p = b.readVarInt(), n = b.readVarInt(), l = b.readVarInt();
        if (l < 0 || l > 256 || b.readableBytes() < l * 12)
            throw new IllegalArgumentException("table length");
        long[] k = new long[l];
        int[] v = new int[l];
        for (int i = 0; i < l; i++) {
            k[i] = b.readLong();
            v[i] = b.readInt();
        }
        return new VersionTablePayload(e, r, root, h, p, n, k, v);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
