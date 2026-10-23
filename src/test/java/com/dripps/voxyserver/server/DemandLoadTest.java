package com.dripps.voxyserver.server;

import com.dripps.voxyserver.network.*;
import com.dripps.voxyserver.sync.*;

import io.netty.buffer.Unpooled;

import me.cortex.voxy.common.world.WorldEngine;

import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;

import java.util.*;

/**
 * Synthetic 32768-square world, not real game traffic. Uses actual shared index and wire codecs.
 */
public final class DemandLoadTest {
    public static void runAllTests() {
        var dim = Identifier.parse("minecraft:overworld");
        var index = new DemandIndex();
        Map<Long, Long> groups = new TreeMap<>();
        List<Long> keys = new ArrayList<>();
        // L4 is 512 blocks wide; a -64..319 vertical world intersects two such vertical nodes.
        for (int y = -1; y <= 0; y++)
            for (int z = -32; z < 32; z++)
                for (int x = -32; x < 32; x++) {
                    long key = WorldEngine.getWorldSectionId(4, x, y, z);
                    keys.add(key);
                    groups.merge(DemandGroup.of(key), 1L << DemandGroup.slot(key), (a, b) -> a | b);
                    index.request(dim, key, null, null);
                }
        require(keys.size() == 8192 && groups.size() == 512, "world/LOD geometry");
        while (index.pending() > 0)
            index.computeOne(
                    p ->
                            new LODSectionPayload(
                                    dim,
                                    p.key().node(),
                                    new int[] {0},
                                    new int[] {0},
                                    new byte[] {0},
                                    new short[] {0},
                                    p.key().node() ^ 0x123456789L));
        long before = index.computed, start = System.nanoTime(), proofs = 0;
        for (int player = 0; player < 256; player++)
            for (var group : groups.entrySet()) {
                var proof = index.proof(dim, group.getKey(), group.getValue());
                require(proof != null, "shared hash cache missed");
                var state = new DemandProofState(proofs + 1, group.getValue());
                require(
                        state.summary(
                                        proof.coverage(),
                                        proof.revision(),
                                        proof.hashes(),
                                        proof.coverage(),
                                        DemandGroup.digest(proof.coverage(), proof.hashes()),
                                        true)
                                == LODDemandReplyPayload.MATCH,
                        "unchanged shared demand retransmitted");
                proofs++;
            }
        long elapsed = System.nanoTime() - start;
        require(index.computed == before, "each player recalculated shared hashes");
        var raw = Unpooled.buffer();
        var buf = new RegistryFriendlyByteBuf(raw, RegistryAccess.EMPTY);
        long flatBytes = 0, groupBytes = 0, changedBytes = 0;
        int id = 0;
        try {
            for (int base = 0; base < keys.size(); base += 128) {
                long[] page = new long[128], hashes = new long[128];
                for (int i = 0; i < 128; i++) {
                    page[i] = keys.get(base + i);
                    hashes[i] = page[i] ^ 0x123456789L;
                }
                LODManifestPayload.CODEC.encode(
                        buf,
                        new LODManifestPayload(
                                dim,
                                0x12345678L,
                                1,
                                page,
                                hashes,
                                base + 128 == keys.size() ? 1 : 0,
                                base / 128));
                flatBytes += raw.readableBytes();
                raw.clear();
            }
            for (var group : groups.entrySet()) {
                var proof = index.proof(dim, group.getKey(), group.getValue());
                id++;
                LODDemandPayload.CODEC.encode(
                        buf,
                        new LODDemandPayload(
                                dim,
                                0x12345678L,
                                id,
                                0,
                                group.getKey(),
                                group.getValue(),
                                proof.coverage(),
                                proof.revision(),
                                DemandGroup.digest(proof.coverage(), proof.hashes()),
                                new long[0]));
                LODDemandReplyPayload.CODEC.encode(
                        buf,
                        new LODDemandReplyPayload(
                                dim,
                                0x12345678L,
                                id,
                                group.getKey(),
                                0,
                                group.getValue(),
                                proof.coverage(),
                                proof.revision(),
                                0));
                groupBytes += raw.readableBytes();
                raw.clear();
                if (id % 100
                        == 0) { // About 1% of group summaries differ: compare only these groups'
                                // covered leaves.
                    LODDemandPayload.CODEC.encode(
                            buf,
                            new LODDemandPayload(
                                    dim,
                                    0x12345678L,
                                    id,
                                    1,
                                    group.getKey(),
                                    group.getValue(),
                                    proof.coverage(),
                                    proof.revision(),
                                    0,
                                    proof.hashes()));
                    LODDemandReplyPayload.CODEC.encode(
                            buf,
                            new LODDemandReplyPayload(
                                    dim,
                                    0x12345678L,
                                    id,
                                    group.getKey(),
                                    3,
                                    group.getValue(),
                                    proof.coverage(),
                                    proof.revision(),
                                    1L << Long.numberOfTrailingZeros(proof.coverage())));
                    changedBytes += raw.readableBytes();
                    raw.clear();
                }
            }
        } finally {
            raw.release();
        }
        require(
                groupBytes + changedBytes < flatBytes * 0.9,
                "two-stage audit did not clear the 10% metadata saving threshold");
        System.out.printf(
                "PASS DemandLoadTest: 256 clients x 512 groups = %d proofs, %.2f ms, 0 additional"
                    + " hashes; flat audit=%d B, unchanged grouped=%d B (%.1f%% less), ~1%%"
                    + " differing groups=%d B; no terrain/TCP/compression/E2E claim%n",
                proofs,
                elapsed / 1e6,
                flatBytes,
                groupBytes,
                100.0 * (1 - (double) groupBytes / flatBytes),
                groupBytes + changedBytes);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
