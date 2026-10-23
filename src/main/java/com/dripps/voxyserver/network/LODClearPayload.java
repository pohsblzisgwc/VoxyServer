package com.dripps.voxyserver.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.Optional;

// Legacy signal: invalidate stream verification/work for a dimension or all dimensions.
// Persistent client terrain is overwrite-only and must never be erased by this signal.
public record LODClearPayload(
        Optional<Identifier> dimension
) implements CustomPacketPayload {

    public static final Type<LODClearPayload> TYPE =
            new Type<>(Identifier.parse("voxyserver:lod_clear"));

    public static final StreamCodec<RegistryFriendlyByteBuf, LODClearPayload> CODEC =
            StreamCodec.of(LODClearPayload::write, LODClearPayload::read);

    // clear all dimensions
    public static LODClearPayload clearAll() {
        return new LODClearPayload(Optional.empty());
    }

    // clear specific dimension
    public static LODClearPayload clearDimension(Identifier dimension) {
        return new LODClearPayload(Optional.of(dimension));
    }

    private static void write(RegistryFriendlyByteBuf buf, LODClearPayload payload) {
        buf.writeBoolean(payload.dimension.isPresent());
        payload.dimension.ifPresent(buf::writeIdentifier);
    }

    private static LODClearPayload read(RegistryFriendlyByteBuf buf) {
        boolean hasDimension = buf.readBoolean();
        Optional<Identifier> dimension = hasDimension
                ? Optional.of(buf.readIdentifier())
                : Optional.empty();
        return new LODClearPayload(dimension);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
