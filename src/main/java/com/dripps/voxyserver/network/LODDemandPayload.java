package com.dripps.voxyserver.network;

import com.dripps.voxyserver.sync.DemandGroup;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * One bounded group request. SUMMARY is the first proof, DETAIL supplies only differing group's
 * leaves.
 */
public record LODDemandPayload(
        Identifier dimension,
        long epoch,
        long requestId,
        int phase,
        long group,
        long wanted,
        long coverage,
        long revision,
        long root,
        long[] hashes)
        implements CustomPacketPayload {
    public static final int SUMMARY = 0, DETAIL = 1, CANCEL = 2;
    public static final Type<LODDemandPayload> TYPE =
            new Type<>(Identifier.parse("voxyserver:demand_v5"));
    public static final StreamCodec<RegistryFriendlyByteBuf, LODDemandPayload> CODEC =
            StreamCodec.of(LODDemandPayload::write, LODDemandPayload::read);

    public LODDemandPayload {
        if (dimension == null
                || epoch <= 0
                || requestId <= 0
                || !DemandGroup.validGroup(group)
                || phase < SUMMARY
                || phase > CANCEL
                || wanted == 0
                || (coverage & ~wanted) != 0
                || hashes == null
                || hashes.length != (phase == DETAIL ? 64 : 0))
            throw new IllegalArgumentException("invalid demand");
        hashes = hashes.clone();
        if (phase == DETAIL)
            for (int i = 0; i < 64; i++) if ((coverage & (1L << i)) == 0) hashes[i] = 0;
    }

    private static void write(RegistryFriendlyByteBuf b, LODDemandPayload p) {
        b.writeIdentifier(p.dimension);
        b.writeVarLong(p.epoch);
        b.writeVarLong(p.requestId);
        b.writeByte(p.phase);
        b.writeLong(p.group);
        b.writeLong(p.wanted);
        b.writeLong(p.coverage);
        b.writeLong(p.revision);
        b.writeLong(p.root);
        if (p.phase == DETAIL)
            for (int i = 0; i < 64; i++)
                if ((p.coverage & (1L << i)) != 0) b.writeLong(p.hashes[i]);
    }

    private static LODDemandPayload read(RegistryFriendlyByteBuf b) {
        Identifier d = b.readIdentifier();
        long e = b.readVarLong(), r = b.readVarLong();
        int p = b.readUnsignedByte();
        long g = b.readLong(),
                w = b.readLong(),
                c = b.readLong(),
                v = b.readLong(),
                root = b.readLong();
        if (p < 0 || p > CANCEL || b.readableBytes() < (p == DETAIL ? Long.bitCount(c) * 8 : 0))
            throw new IllegalArgumentException("truncated demand");
        long[] h = new long[p == DETAIL ? 64 : 0];
        for (int i = 0; i < h.length; i++) if ((c & (1L << i)) != 0) h[i] = b.readLong();
        return new LODDemandPayload(d, e, r, p, g, w, c, v, root, h);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
