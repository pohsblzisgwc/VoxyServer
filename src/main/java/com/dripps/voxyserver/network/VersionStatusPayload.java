package com.dripps.voxyserver.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record VersionStatusPayload(long epoch, long round, int status, int page, int count)
        implements CustomPacketPayload {
    public static final int TABLES_COMPLETE = 0,
            PAGE_DONE = 1,
            DONE = 2,
            BUSY = 3,
            FAILED = 4,
            STALE = 5,
            STARTED = 6;
    public static final Type<VersionStatusPayload> TYPE =
            new Type<>(Identifier.parse("voxyserver:version_status_v6"));
    public static final StreamCodec<RegistryFriendlyByteBuf, VersionStatusPayload> CODEC =
            StreamCodec.of(
                    (b, p) -> {
                        b.writeVarLong(p.epoch);
                        b.writeVarLong(p.round);
                        b.writeByte(p.status);
                        b.writeVarInt(p.page);
                        b.writeVarInt(p.count);
                    },
                    b ->
                            new VersionStatusPayload(
                                    b.readVarLong(),
                                    b.readVarLong(),
                                    b.readUnsignedByte(),
                                    b.readVarInt(),
                                    b.readVarInt()));

    public VersionStatusPayload {
        if (epoch <= 0
                || round <= 0
                || status < 0
                || status > 6
                || page < 0
                || count < 0
                || count > VersionSelectPayload.MAX_PAGES * 256)
            throw new IllegalArgumentException("status bounds");
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
