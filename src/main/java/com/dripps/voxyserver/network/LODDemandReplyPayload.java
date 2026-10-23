package com.dripps.voxyserver.network;

import com.dripps.voxyserver.sync.DemandGroup;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record LODDemandReplyPayload(
        Identifier dimension,
        long epoch,
        long requestId,
        long group,
        int status,
        long wanted,
        long coverage,
        long revision,
        long needed)
        implements CustomPacketPayload {
    public static final int MATCH = 0,
            DIFFERENT = 1,
            PENDING = 2,
            ACCEPTED = 3,
            REJECTED = 4,
            INVALIDATED = 5;
    public static final Type<LODDemandReplyPayload> TYPE =
            new Type<>(Identifier.parse("voxyserver:demand_reply_v5"));
    public static final StreamCodec<RegistryFriendlyByteBuf, LODDemandReplyPayload> CODEC =
            StreamCodec.of(LODDemandReplyPayload::write, LODDemandReplyPayload::read);

    public LODDemandReplyPayload {
        if (dimension == null
                || epoch <= 0
                || requestId <= 0
                || !DemandGroup.validGroup(group)
                || status < 0
                || status > INVALIDATED
                || wanted == 0
                || (coverage & ~wanted) != 0
                || (needed & ~coverage) != 0)
            throw new IllegalArgumentException("invalid demand response");
    }

    private static void write(RegistryFriendlyByteBuf b, LODDemandReplyPayload p) {
        b.writeIdentifier(p.dimension);
        b.writeVarLong(p.epoch);
        b.writeVarLong(p.requestId);
        b.writeLong(p.group);
        b.writeByte(p.status);
        b.writeLong(p.wanted);
        b.writeLong(p.coverage);
        b.writeLong(p.revision);
        b.writeLong(p.needed);
    }

    private static LODDemandReplyPayload read(RegistryFriendlyByteBuf b) {
        return new LODDemandReplyPayload(
                b.readIdentifier(),
                b.readVarLong(),
                b.readVarLong(),
                b.readLong(),
                b.readUnsignedByte(),
                b.readLong(),
                b.readLong(),
                b.readLong(),
                b.readLong());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
