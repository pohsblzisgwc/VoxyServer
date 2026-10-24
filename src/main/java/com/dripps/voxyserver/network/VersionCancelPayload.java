package com.dripps.voxyserver.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record VersionCancelPayload(long epoch, long round) implements CustomPacketPayload {
    public static final Type<VersionCancelPayload> TYPE =
            new Type<>(Identifier.parse("voxyserver:version_cancel_v6"));
    public static final StreamCodec<RegistryFriendlyByteBuf, VersionCancelPayload> CODEC =
            StreamCodec.of(
                    (b, p) -> {
                        b.writeVarLong(p.epoch);
                        b.writeVarLong(p.round);
                    },
                    b -> new VersionCancelPayload(b.readVarLong(), b.readVarLong()));

    public VersionCancelPayload {
        if (epoch <= 0 || round <= 0) throw new IllegalArgumentException("cancel identity");
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
