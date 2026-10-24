package com.dripps.voxyserver.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.UUID;

public record VersionSessionPayload(Identifier dimension, long epoch, UUID world, UUID versions)
        implements CustomPacketPayload {
    public static final Type<VersionSessionPayload> TYPE =
            new Type<>(Identifier.parse("voxyserver:version_session_v6"));
    public static final StreamCodec<RegistryFriendlyByteBuf, VersionSessionPayload> CODEC =
            StreamCodec.of(
                    (b, p) -> {
                        b.writeIdentifier(p.dimension);
                        b.writeVarLong(p.epoch);
                        b.writeUUID(p.world);
                        b.writeUUID(p.versions);
                    },
                    b ->
                            new VersionSessionPayload(
                                    b.readIdentifier(),
                                    b.readVarLong(),
                                    b.readUUID(),
                                    b.readUUID()));

    public VersionSessionPayload {
        if (dimension == null || epoch <= 0 || world == null || versions == null)
            throw new IllegalArgumentException("version session");
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
