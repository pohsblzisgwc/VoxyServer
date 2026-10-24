package com.dripps.voxyserver;

import com.dripps.voxyserver.network.*;

import io.netty.buffer.Unpooled;

import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;

/** Retired wire fixtures only. Production protocol 6 uses VersionedNodePayload. */
final class LegacyNodeWire {
    static PreSerializedLodPayload fromBulk(LODBulkPayload bulk, RegistryAccess registry) {
        var raw = Unpooled.buffer();
        try {
            var b = new RegistryFriendlyByteBuf(raw, registry);
            LODBulkPayload.CODEC.encode(b, bulk);
            byte[] data = new byte[b.readableBytes()];
            b.readBytes(data);
            return new PreSerializedLodPayload(0, 0, bulk.sections().size(), data);
        } finally {
            raw.release();
        }
    }

    static LODBulkPayload decodeBulk(PreSerializedLodPayload packet, RegistryAccess registry) {
        var raw = Unpooled.wrappedBuffer(packet.data());
        try {
            return LODBulkPayload.CODEC.decode(new RegistryFriendlyByteBuf(raw, registry));
        } finally {
            raw.release();
        }
    }
}
