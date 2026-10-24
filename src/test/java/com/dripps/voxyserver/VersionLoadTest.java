package com.dripps.voxyserver;

import com.dripps.voxyserver.sync.version.*;

import me.cortex.voxy.common.world.WorldEngine;

import net.minecraft.resources.Identifier;

import java.nio.file.*;
import java.util.*;

/**
 * Full metadata geometry for a 32768 x 32768 block map; no voxelization, game traffic or TPS claim.
 */
public final class VersionLoadTest {
    public static void runAllTests() throws Exception {
        Path dir = Files.createTempDirectory("voxy-version-load-");
        var dim = Identifier.parse("minecraft:overworld");
        try {
            var index = new VersionIndex(dir.resolve("versions"));
            long start = System.nanoTime();
            index.bootstrap(
                    dim,
                    consumer -> {
                        for (int y = -2; y < 10; y++)
                            for (int z = -512; z < 512; z++)
                                for (int x = -512; x < 512; x++)
                                    consumer.accept(WorldEngine.getWorldSectionId(0, x, y, z));
                    });
            long inventory = System.nanoTime() - start;
            var scope = new VersionScope(0, 0, 1024, -64, 320);
            long[] digests = new long[scope.rootCount()];
            for (int i = 0; i < digests.length; i++)
                digests[i] = index.digest(dim, scope.rootAt(i), scope);
            long computed = index.digestComputations();
            start = System.nanoTime();
            long proofs = 0;
            for (int player = 0; player < 256; player++)
                for (int i = 0; i < digests.length; i++) {
                    if (index.digest(dim, scope.rootAt(i), scope) != digests[i])
                        throw new AssertionError("unchanged version sequence drift");
                    proofs++;
                }
            if (index.digestComputations() != computed)
                throw new AssertionError("each player recomputed version sequences");
            long audit = System.nanoTime() - start;
            long flat = 12_582_912L * 12, grouped = digests.length * 8L;
            if (grouped >= flat / 10)
                throw new AssertionError("version summaries did not reduce metadata");
            System.out.printf(
                    "PASS VersionLoadTest: 12,582,912 LOD0 versions / 32768-square map, inventory"
                        + " %.2f ms; 256 clients x %d roots = %d cached summaries in %.2f ms; leaf"
                        + " metadata=%d B vs summary digests=%d B; synthetic, excludes"
                        + " framing/data/TCP/TPS%n",
                    inventory / 1e6, digests.length, proofs, audit / 1e6, flat, grouped);
        } finally {
            try (var paths = Files.walk(dir)) {
                for (var p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
    }
}
