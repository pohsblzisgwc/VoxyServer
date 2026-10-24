package com.dripps.voxyserver.server;

import com.dripps.voxyserver.network.*;
import com.dripps.voxyserver.sync.*;

import me.cortex.voxy.common.world.WorldEngine;

import net.minecraft.resources.Identifier;

import java.util.*;

/** Deterministic cold-index contention using the actual queues and proof/ACK state. */
public final class DeliveryProgressTest {
    private static final Identifier DIM = Identifier.parse("minecraft:overworld");

    private static LODSectionPayload load(DemandIndex.Probe p) {
        return new LODSectionPayload(
                DIM,
                p.key().node(),
                new int[] {0},
                new int[] {0},
                new byte[] {0},
                new short[] {0},
                19 + p.key().node());
    }

    public static void runAllTests() {
        var index = new DemandIndex();
        // Fill the fingerprint index, overrunning the 128-payload cache, as a large cold view does.
        for (int i = 0; i < 8192; i++) {
            long key = WorldEngine.getWorldSectionId(0, i, 0, 0);
            index.request(DIM, key, null, null);
        }
        while (index.pending() != 0) index.computeOne(DeliveryProgressTest::load);
        long target = WorldEngine.getWorldSectionId(0, 0, 0, 0);
        require(
                index.get(DIM, target) != null
                        && index.payload(new DemandIndex.Key(DIM, target)) == null,
                "fixture did not evict an already-proven payload");
        // A normal backlog used to sit ahead of this payload refill.
        for (int i = 8192; i < 16383; i++)
            index.request(DIM, WorldEngine.getWorldSectionId(0, i, 0, 0), null, null);
        require(index.request(DIM, target, null, null, true), "delivery refill rejected");
        require(index.pending() == 8192, "probe bound changed");
        index.computeOne(DeliveryProgressTest::load);
        require(
                index.payload(new DemandIndex.Key(DIM, target)) != null,
                "ready delivery starved behind 8191 unrelated hash probes");
        long normalBefore = index.computed - index.deliveryComputations;
        int delivered = 0;
        long start = System.nanoTime();
        // 256 players share 64 evicted nodes. Deduplicate promotions; release each payload
        // promptly.
        for (int round = 0; round < 64; round++) {
            long key = WorldEngine.getWorldSectionId(0, round, 0, 0);
            for (int player = 0; player < 256; player++)
                require(index.request(DIM, key, null, null, true), "shared delivery rejected");
            int turns = 0;
            do {
                require(index.computeOne(DeliveryProgressTest::load), "delivery work lost");
                require(++turns <= 2, "delivery did not progress within fair service window");
            } while (index.payload(new DemandIndex.Key(DIM, key)) == null);
            delivered += 256;
            require(index.pending() <= 8192, "priority promotion exceeded memory bound");
        }
        require(
                index.computed - index.deliveryComputations > normalBefore,
                "delivery priority permanently starved normal index work");
        long group = DemandGroup.of(target), wanted = 1L << DemandGroup.slot(target);
        var gate = new DemandProofState(1, wanted);
        require(!gate.affectedBy(wanted), "unpublished PENDING request invalidated");
        var proof = index.proof(DIM, group, wanted);
        require(proof != null, "delivery lost fingerprint");
        gate.summary(proof.coverage(), proof.revision(), proof.hashes(), 0, 0, true);
        for (int i = 0; i < 100000; i++)
            require(
                    !gate.affectedBy(1L << ((DemandGroup.slot(target) + 1) & 63)),
                    "unrequested sibling edit invalidated snapshot");
        require(gate.affectedBy(wanted), "actual requested edit was ignored");
        require(
                index.current(DIM, group, wanted, gate.revision),
                "late notification of already-indexed content must keep the snapshot");
        index.invalidate(DIM, target);
        require(
                !index.current(DIM, group, wanted, gate.revision),
                "H1 remained current after edit");
        index.request(DIM, target, null, null, true);
        while (index.get(DIM, target) == null) index.computeOne(DeliveryProgressTest::load);
        var next = index.proof(DIM, group, wanted);
        require(next.revision() != gate.revision, "H2 reused old revision");
        gate.invalidated = true;
        require(!gate.confirm(1, wanted, true), "late old ACK revived invalidated snapshot");
        System.out.printf(
                "PASS DeliveryProgressTest: 8192 cold fingerprints, 8191 queued probes, first"
                    + " delivery in one compute turn, %d shared player-node deliveries in %.2f ms;"
                    + " normal work advances, 100000 sibling edits preserve proof; synthetic, no"
                    + " network/TPS claim%n",
                delivered, (System.nanoTime() - start) / 1e6);
        index.clear();
        require(index.pending() == 0, "shutdown leaked priority work");
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
