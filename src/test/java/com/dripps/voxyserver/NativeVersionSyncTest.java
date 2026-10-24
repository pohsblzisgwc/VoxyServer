package com.dripps.voxyserver;

import com.dripps.voxyserver.network.*;
import com.dripps.voxyserver.sync.*;
import com.dripps.voxyserver.sync.version.*;

import me.cortex.voxy.common.voxelization.*;
import me.cortex.voxy.common.world.*;
import me.cortex.voxy.common.world.other.Mapper;

import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Blocks;

import java.nio.file.*;
import java.util.*;

/** Real Voxy native comparison/publication and int32 wire; no semantic hash is involved. */
public final class NativeVersionSyncTest {
    public static void run() throws Exception {
        var server = NativeLodIntegrationTest.memoryWorld();
        var client = NativeLodIntegrationTest.memoryWorld();
        var biomes = NativeLodIntegrationTest.biomes();
        var dim = Identifier.parse("minecraft:overworld");
        Path dir = Files.createTempDirectory("voxy-native-versions-");
        var versions = new VersionIndex(dir.resolve("index"));
        try {
            for (int value : new int[] {0, 15}) {
                var light = SkyLightSnapshot.inherited((x, z) -> value);
                for (int y = 0; y < 16; y++)
                    require(light.get(7, y, 9) == value, "implicit skylight value lost");
            }
            var inherited = SkyLightSnapshot.inherited((x, z) -> (x + z) & 15);
            for (int y = 0; y < 16; y++)
                for (int x = 0; x < 16; x++)
                    for (int z = 0; z < 16; z++)
                        require(
                                inherited.get(x, y, z) == ((x + z) & 15),
                                "inherited skylight columns mixed");
            var upperLayer = new net.minecraft.world.level.chunk.DataLayer();
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++)
                        upperLayer.set(x, y, z, (x + y * 3 + z * 5) & 15);
            var inheritedLayer =
                    SkyLightSnapshot.inheritAbove(4, 10, Map.of(6, upperLayer));
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++)
                        require(
                                inheritedLayer.get(x, y, z) == upperLayer.get(x, y, z),
                                "missing section did not inherit the full upper sky-light layer");
            var defaultSky = SkyLightSnapshot.inheritAbove(10, 10, Map.of());
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++)
                        require(defaultSky.get(x, y, z) == 15, "top sky-light default was not 15");
            versions.bootstrap(dim, c -> {});
            server.setDirtyCallback(
                    (s, flags, neighbors) -> {
                        require(Thread.holdsLock(server), "publication escaped native lock");
                        versions.changed(dim, s.key);
                    });
            int stone = server.getMapper().getIdForBlockState(Blocks.STONE.defaultBlockState());
            client.getMapper().getIdForBlockState(Blocks.DIRT.defaultBlockState());
            client.getMapper().getIdForBlockState(Blocks.STONE.defaultBlockState());
            server.getMapper().getIdForBiome(biomes.get(0).orElseThrow());
            var section = VoxelizedSection.createEmpty().setPosition(0, 4, 0);
            Arrays.fill(section.section, 0, 4096, Mapper.composeMappingId((byte) 15, stone, 0));
            section.lvl0NonAirCount = 4096;
            WorldVoxilizedSectionMipper.mipSection(section, server.getMapper());
            WorldUpdater.insertUpdate(server, section);
            long key = WorldEngine.getWorldSectionId(0, 0, 2, 0);
            require(versions.get(dim, key) == 1, "first update did not publish version 1");
            for (int i = 0; i < 100; i++) WorldUpdater.insertUpdate(server, section);
            require(versions.get(dim, key) == 1, "unchanged native content incremented version");
            // Four source columns/eight octants publish one LOD0 version together.
            synchronized (server) {
                versions.beginBatch();
                try {
                    for (int z = 0; z < 2; z++)
                        for (int x = 0; x < 2; x++)
                            for (int y = 0; y < 2; y++) {
                                var p = VoxelizedSection.createEmpty().setPosition(x, 4 + y, z);
                                Arrays.fill(
                                        p.section,
                                        0,
                                        4096,
                                        Mapper.composeMappingId((byte) 14, stone, 0));
                                p.lvl0NonAirCount = 4096;
                                WorldVoxilizedSectionMipper.mipSection(p, server.getMapper());
                                WorldUpdater.insertUpdate(server, p);
                            }
                } finally {
                    versions.endBatch();
                }
            }
            require(versions.get(dim, key) == 2, "eight source octants published more than once");
            var source = SectionDataAccess.acquireStored(server, key);
            VersionedNodePayload payload;
            try {
                payload =
                        VersionedNodePayload.encode(
                                key,
                                versions.get(dim, key),
                                source.copyData(),
                                source.getNonEmptyChildren(),
                                server.getMapper(),
                                biomes);
            } finally {
                source.release();
            }
            var wire = VersionedNodePayload.encodeBatch(List.of(payload), RegistryAccess.EMPTY);
            var decoded = VersionedNodePayload.decodeBatch(wire, RegistryAccess.EMPTY).getFirst();
            long[] palette = new long[decoded.states().length];
            for (int i = 0; i < palette.length; i++)
                palette[i] =
                        Mapper.composeMappingId(
                                decoded.light()[i],
                                client.getMapper()
                                        .getIdForBlockState(
                                                net.minecraft.world.level.block.Block
                                                        .BLOCK_STATE_REGISTRY
                                                        .byId(decoded.states()[i])),
                                client.getMapper()
                                        .getIdForBiome(
                                                biomes.get(decoded.biomes()[i]).orElseThrow()));
            NativeNodeInstaller.install(
                    client, decoded.key(), palette, decoded.indices(), decoded.children());
            var installed = SectionDataAccess.acquireStored(client, key);
            try {
                for (long value : installed.copyData())
                    require(
                            client.getMapper()
                                            .getBlockStateFromBlockId(Mapper.getBlockId(value))
                                            .equals(Blocks.STONE.defaultBlockState())
                                    && Mapper.getLightId(value) == 14,
                            "versioned data/remapping/light mismatch");
            } finally {
                installed.release();
            }
            for (int level = 1; level <= 4; level++) {
                var parent =
                        SectionDataAccess.acquireStored(
                                client, WorldEngine.getWorldSectionId(level, 0, 2 >> level, 0));
                require(parent != null, "LOD0 delivery did not build parent " + level);
                parent.release();
            }
            System.out.println(
                    "PASS NativeVersionSyncTest: native no-op comparison, one version/eight source"
                        + " octants, LOD0-only int32 payload, real mapper remapping and local"
                        + " LOD1-4 parents");
        } finally {
            server.free();
            client.free();
            try (var paths = Files.walk(dir)) {
                for (var p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
