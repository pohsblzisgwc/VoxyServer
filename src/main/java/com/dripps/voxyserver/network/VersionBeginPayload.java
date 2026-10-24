package com.dripps.voxyserver.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Small admission header: the server freezes the scope before the client enumerates its cache. */
public record VersionBeginPayload(long epoch, long round, int radiusChunks)
        implements CustomPacketPayload {
    public static final Type<VersionBeginPayload> TYPE =
            new Type<>(Identifier.parse("voxyserver:version_begin_v6"));
    public static final StreamCodec<RegistryFriendlyByteBuf, VersionBeginPayload> CODEC =
            StreamCodec.of(
                    (b, p) -> {
                        b.writeVarLong(p.epoch);
                        b.writeVarLong(p.round);
                        b.writeVarInt(p.radiusChunks);
                    },
                    b -> new VersionBeginPayload(b.readVarLong(), b.readVarLong(), b.readVarInt()));

    public VersionBeginPayload {
        if (epoch <= 0 || round <= 0 || radiusChunks < 1 || radiusChunks > 4096)
            throw new IllegalArgumentException("version begin bounds");
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
