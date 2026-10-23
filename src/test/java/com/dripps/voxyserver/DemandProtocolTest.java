package com.dripps.voxyserver;

import com.dripps.voxyserver.network.*;
import com.dripps.voxyserver.sync.*;

import io.netty.buffer.Unpooled;

import me.cortex.voxy.common.world.WorldEngine;

import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;

import java.util.*;

public final class DemandProtocolTest {
    private static final Identifier DIM = Identifier.parse("minecraft:overworld");

    public static void runAllTests() {
        require(
                NativeLodUnits.radiusChunks(16) == 512 && NativeLodUnits.radiusChunks(32) == 1024,
                "Voxy top-level view units misread as L0 units");
        Set<Long> levels = new HashSet<>();
        for (int lod = 0; lod <= 4; lod++)
            for (int x = -12; x < 12; x++)
                for (int y = -12; y < 12; y++)
                    for (int z = -5; z < 5; z++) {
                        long key = WorldEngine.getWorldSectionId(lod, x, y, z),
                                group = DemandGroup.of(key);
                        require(DemandGroup.validGroup(group), "canonical group rejected");
                        require(
                                DemandGroup.node(group, DemandGroup.slot(key)) == key,
                                "native LOD/group round trip");
                        if (x == 0 && y == 0 && z == 0) levels.add(group);
                    }
        require(levels.size() == 5, "native LOD identities collided");
        long group = DemandGroup.of(WorldEngine.getWorldSectionId(4, 0, 0, 0));
        long[] hashes = new long[64];
        Arrays.fill(hashes, 91);
        long root = DemandGroup.digest(-1L, hashes);
        hashes[63] = 92;
        require(DemandGroup.digest(-1L, hashes) != root, "last leaf excluded from summary");
        hashes[63] = 0;
        require(DemandGroup.digest(-1L, hashes) == 0, "unknown leaf claimed equality");
        require(
                DemandGroup.digest(1, hashes) != DemandGroup.digest(2, hashes),
                "membership excluded from summary");
        var raw = Unpooled.buffer();
        var buf = new RegistryFriendlyByteBuf(raw, RegistryAccess.EMPTY);
        try {
            for (int phase = 0; phase <= 2; phase++) {
                raw.clear();
                var request =
                        new LODDemandPayload(
                                DIM,
                                123,
                                7,
                                phase,
                                group,
                                -1L,
                                3,
                                22,
                                root,
                                phase == 1 ? hashes : new long[0]);
                LODDemandPayload.CODEC.encode(buf, request);
                int bytes = raw.readableBytes();
                var copy = LODDemandPayload.CODEC.decode(buf);
                require(
                        copy.group() == group
                                && copy.requestId() == 7
                                && copy.epoch() == 123
                                && copy.wanted() == -1L
                                && Arrays.equals(copy.hashes(), request.hashes()),
                        "demand wire identity");
                require(bytes < 620, "demand size unbounded");
                raw.clear();
                LODDemandPayload.CODEC.encode(buf, request);
                raw.writerIndex(raw.writerIndex() - 1);
                invalid(() -> LODDemandPayload.CODEC.decode(buf));
            }
            for (int status = 0; status <= 5; status++) {
                raw.clear();
                var reply = new LODDemandReplyPayload(DIM, 123, 7, group, status, 7, 3, 11, 2);
                LODDemandReplyPayload.CODEC.encode(buf, reply);
                require(reply.equals(LODDemandReplyPayload.CODEC.decode(buf)), "reply round trip");
            }
            raw.clear();
            var packet =
                    new PreSerializedLodPayload(123, 9, 1, new byte[] {1, 2, 3})
                            .withDemand(group, 7);
            PreSerializedLodPayload.CODEC.encode(buf, packet);
            var decoded = PreSerializedLodPayload.CODEC.decode(buf);
            require(
                    decoded.streamEpoch() == 123
                            && decoded.batchId() == 9
                            && decoded.demandRequestId() == 7
                            && decoded.demandGroup() == group,
                    "delivery lost its proof fence");
        } finally {
            raw.release();
        }
        invalid(
                () ->
                        new LODDemandPayload(
                                DIM,
                                1,
                                1,
                                0,
                                WorldEngine.getWorldSectionId(4, 0, 127, 0),
                                1,
                                0,
                                0,
                                0,
                                new long[0]));
        invalid(
                () ->
                        new LODDemandPayload(
                                DIM,
                                1,
                                1,
                                0,
                                WorldEngine.getWorldSectionId(4, 1 << 22, 0, 0),
                                1,
                                0,
                                0,
                                0,
                                new long[0]));
        invalid(() -> new LODDemandPayload(DIM, 1, 1, 0, group, 0, 0, 0, 0, new long[0]));
        invalid(() -> new LODDemandPayload(DIM, 1, 1, 1, group, 1, 2, 0, 0, hashes));
        invalid(() -> new LODDemandReplyPayload(DIM, 1, 1, group, 3, 1, 1, 0, 2));
        require(
                DemandGroup.allowed(
                        WorldEngine.getWorldSectionId(4, 31, 0, 0), 0, 0, 1024, -64, 320),
                "1024-chunk native far demand rejected");
        require(
                !DemandGroup.allowed(
                        WorldEngine.getWorldSectionId(4, 33, 0, 0), 0, 0, 1024, -64, 320),
                "out of range demand accepted");
        require(
                !DemandGroup.allowed(
                        WorldEngine.getWorldSectionId(5, 0, 0, 0), 0, 0, 512, -64, 320),
                "unsupported LOD accepted");
        System.out.println(
                "PASS DemandProtocolTest: native levels, coordinate aliases, two proofs, bounded"
                    + " codecs and delivery fencing");
    }

    private static void invalid(Runnable test) {
        try {
            test.run();
            throw new AssertionError("invalid wire accepted");
        } catch (IllegalArgumentException | IndexOutOfBoundsException expected) {
        }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
