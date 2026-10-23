package com.dripps.voxyserver;

import com.dripps.voxyserver.network.LODManifestAckPayload;
import com.dripps.voxyserver.network.LODManifestPayload;
import com.dripps.voxyserver.network.LODStreamSessionPayload;
import com.dripps.voxyserver.util.SemanticSectionHash;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;

import java.util.Arrays;
import java.util.UUID;

public final class SynchronizationProtocolTest {
    public static void runAllTests() {
        Identifier dimension = Identifier.parse("minecraft:overworld");
        long[] keys = new long[LODManifestPayload.MAX_ENTRIES], hashes = new long[keys.length];
        for (int i = 0; i < keys.length; i++) { keys[i] = -8000L + i; hashes[i] = i * 301L; }
        var page = new LODManifestPayload(dimension, 123L, 9L, keys, hashes, LODManifestPayload.PART, 513L);
        var raw = Unpooled.buffer();
        try {
            var buf = new RegistryFriendlyByteBuf(raw, RegistryAccess.EMPTY);
            LODManifestPayload.CODEC.encode(buf, page);
            var decoded = LODManifestPayload.CODEC.decode(buf);
            require(Arrays.equals(decoded.keys(), keys) && Arrays.equals(decoded.hashes(), hashes), "page arrays changed");
            require(decoded.pageId() == 513L && decoded.requestId() == 9L && decoded.streamEpoch() == 123L,
                    "page identity changed");
            raw.clear();
            var ack = new LODManifestAckPayload(dimension, 123L, 9L, 513L, false);
            LODManifestAckPayload.CODEC.encode(buf, ack);
            var decodedAck = LODManifestAckPayload.CODEC.decode(buf);
            require(!decodedAck.retry() && decodedAck.pageId() == 513L, "ACK status changed");
            raw.clear();
            var session = new LODStreamSessionPayload(dimension, 123L, 9L, true, UUID.randomUUID(), SemanticSectionHash.VERSION);
            LODStreamSessionPayload.CODEC.encode(buf, session);
            require(session.equals(LODStreamSessionPayload.CODEC.decode(buf)), "world/hash namespace did not round-trip");
            raw.clear();
            LODManifestAckPayload.CODEC.encode(buf, ack);
            raw.writerIndex(raw.writerIndex() - 1);
            try {
                LODManifestAckPayload.CODEC.decode(buf);
                throw new AssertionError("truncated ACK accepted");
            } catch (IllegalArgumentException | IndexOutOfBoundsException expected) { }
        } finally { raw.release(); }
        invalid(() -> new LODManifestPayload(dimension, 1, 1, keys, hashes, 0, -1));
        invalid(() -> new LODManifestAckPayload(dimension, 1, 1, -1, false));
        invalid(() -> new LODStreamSessionPayload(dimension, 1, 1, true, UUID.randomUUID(), -1));
        System.out.println("PASS SynchronizationProtocolTest: pages, ACKs, namespaces, truncation and identity bounds");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void invalid(Runnable action) {
        try { action.run(); throw new AssertionError("invalid protocol input accepted"); }
        catch (IllegalArgumentException expected) { }
    }
}
