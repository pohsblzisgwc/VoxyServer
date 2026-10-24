package com.dripps.voxyserver.network;

import com.dripps.voxyserver.sync.version.*;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record VersionSummaryPayload(
        Identifier dimension,
        long epoch,
        long round,
        VersionScope scope,
        int page,
        int pages,
        long[] digests)
        implements CustomPacketPayload {
    public static final Type<VersionSummaryPayload> TYPE =
            new Type<>(Identifier.parse("voxyserver:version_summary_v6"));
    public static final StreamCodec<RegistryFriendlyByteBuf, VersionSummaryPayload> CODEC =
            StreamCodec.of(VersionSummaryPayload::write, VersionSummaryPayload::read);

    public VersionSummaryPayload {
        if (dimension == null
                || scope == null
                || epoch <= 0
                || round <= 0
                || pages != (scope.rootCount() + 255) / 256
                || page < 0
                || page >= pages
                || digests == null
                || digests.length != Math.min(256, scope.rootCount() - page * 256))
            throw new IllegalArgumentException("invalid version summary");
        digests = digests.clone();
    }

    private static void write(RegistryFriendlyByteBuf b, VersionSummaryPayload p) {
        b.writeIdentifier(p.dimension);
        b.writeVarLong(p.epoch);
        b.writeVarLong(p.round);
        b.writeInt(p.scope.centerX());
        b.writeInt(p.scope.centerZ());
        b.writeVarInt(p.scope.radiusChunks());
        b.writeInt(p.scope.minY());
        b.writeInt(p.scope.maxY());
        b.writeInt(p.scope.focusY());
        b.writeVarInt(p.page);
        b.writeVarInt(p.pages);
        b.writeVarInt(p.digests.length);
        for (long h : p.digests) b.writeLong(h);
    }

    private static VersionSummaryPayload read(RegistryFriendlyByteBuf b) {
        var d = b.readIdentifier();
        long e = b.readVarLong(), r = b.readVarLong();
        var s =
                new VersionScope(
                        b.readInt(),
                        b.readInt(),
                        b.readVarInt(),
                        b.readInt(),
                        b.readInt(),
                        b.readInt());
        int p = b.readVarInt(), n = b.readVarInt(), l = b.readVarInt();
        if (l < 1 || l > 256 || b.readableBytes() < l * 8)
            throw new IllegalArgumentException("summary length");
        long[] h = new long[l];
        for (int i = 0; i < l; i++) h[i] = b.readLong();
        return new VersionSummaryPayload(d, e, r, s, p, n, h);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
