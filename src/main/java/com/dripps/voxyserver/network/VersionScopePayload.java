package com.dripps.voxyserver.network;

import com.dripps.voxyserver.sync.version.VersionScope;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record VersionScopePayload(long epoch, long round, Identifier dimension, VersionScope scope)
        implements CustomPacketPayload {
    public static final Type<VersionScopePayload> TYPE =
            new Type<>(Identifier.parse("voxyserver:version_scope_v6"));
    public static final StreamCodec<RegistryFriendlyByteBuf, VersionScopePayload> CODEC =
            StreamCodec.of(
                    (b, p) -> {
                        b.writeVarLong(p.epoch);
                        b.writeVarLong(p.round);
                        b.writeIdentifier(p.dimension);
                        b.writeInt(p.scope.centerX());
                        b.writeInt(p.scope.centerZ());
                        b.writeVarInt(p.scope.radiusChunks());
                        b.writeInt(p.scope.minY());
                        b.writeInt(p.scope.maxY());
                        b.writeInt(p.scope.focusY());
                    },
                    b ->
                            new VersionScopePayload(
                                    b.readVarLong(),
                                    b.readVarLong(),
                                    b.readIdentifier(),
                                    new VersionScope(
                                            b.readInt(),
                                            b.readInt(),
                                            b.readVarInt(),
                                            b.readInt(),
                                            b.readInt(),
                                            b.readInt())));

    public VersionScopePayload {
        if (epoch <= 0 || round <= 0 || dimension == null || scope == null)
            throw new IllegalArgumentException("version scope identity");
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
