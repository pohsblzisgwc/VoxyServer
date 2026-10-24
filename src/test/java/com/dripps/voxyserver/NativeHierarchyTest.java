package com.dripps.voxyserver;

import com.dripps.voxyserver.sync.*;

import me.cortex.voxy.common.voxelization.*;
import me.cortex.voxy.common.world.*;
import me.cortex.voxy.common.world.other.Mapper;

import net.minecraft.world.level.block.Blocks;

import java.util.*;

/** Compare received native nodes against Voxy's real WorldUpdater, including ocean boundaries. */
public final class NativeHierarchyTest {
    public static void run() {
        var reference = NativeLodIntegrationTest.memoryWorld();
        var remote = NativeLodIntegrationTest.memoryWorld();
        try {
            for (var world : List.of(reference, remote)) {
                world.getMapper().getIdForBlockState(Blocks.STONE.defaultBlockState());
                world.getMapper().getIdForBlockState(Blocks.WATER.defaultBlockState());
                world.getMapper().getIdForBlockState(Blocks.DIRT.defaultBlockState());
            }
            int stone = reference.getMapper().getIdForBlockState(Blocks.STONE.defaultBlockState());
            int water = reference.getMapper().getIdForBlockState(Blocks.WATER.defaultBlockState());
            int dirt = reference.getMapper().getIdForBlockState(Blocks.DIRT.defaultBlockState());
            long stoneValue = Mapper.composeMappingId((byte) 15, stone, 0),
                    waterValue = Mapper.composeMappingId((byte) 15, water, 0),
                    dirtValue = Mapper.composeMappingId((byte) 15, dirt, 0);
            long[] sentinel = new long[32768];
            Arrays.fill(sentinel, dirtValue);
            nativeInsert(reference, 1, 1, 0, sentinel);
            install(remote, 0, 1, 1, 0, sentinel, (byte) 255);
            var watched = new ArrayList<Long>();
            remote.setDirtyCallback(
                    (s, flags, neighbors) -> {
                        require(Thread.holdsLock(remote), "hierarchy write escaped world monitor");
                        require(
                                (flags & WorldEngine.DEFAULT_UPDATE_FLAGS) != 0,
                                "no render flags for changed ancestor");
                        if ((flags & WorldEngine.UPDATE_TYPE_BLOCK_BIT) != 0)
                            require(neighbors == 63, "boundary neighbors not invalidated");
                        watched.add(s.key);
                    });
            for (int x = -1; x <= 0; x++)
                for (int z = -1; z <= 0; z++) {
                    long[] data = new long[32768];
                    for (int y = 0; y < 32; y++)
                        for (int pz = 0; pz < 32; pz++)
                            for (int px = 0; px < 32; px++)
                                data[px | (pz << 5) | (y << 10)] =
                                        y < 16
                                                ? ((px + 3 * pz) % 7 == 0 ? dirtValue : stoneValue)
                                                : Mapper.withLight(
                                                        waterValue, (px + 3 * pz + y) & 15);
                    nativeInsert(reference, x, 1, z, data);
                    install(remote, 0, x, 1, z, data, (byte) 255);
                }
            assertAncestors(reference, remote);
            for (int level = 1; level <= 4; level++) {
                int expected = level;
                require(
                        watched.stream().anyMatch(key -> WorldEngine.getLevel(key) == expected),
                        "ancestor did not emit mesh/topology update at LOD " + level);
            }
            var before = read(remote, 0, 1, 1, 0).data();
            long[] air = new long[32768];
            nativeInsert(reference, 0, 1, 0, air);
            install(remote, 0, 0, 1, 0, air, (byte) 0);
            assertAncestors(reference, remote);
            require(
                    Arrays.equals(before, read(remote, 0, 1, 1, 0).data()),
                    "neighbor/sibling terrain erased by air replacement");
            // A coarse packet must not rewrite independently acquired finer sections.
            var fineBefore = read(remote, 0, -1, 1, -1).data();
            var coarse = read(reference, 2, -1, 0, -1);
            install(remote, 2, -1, 0, -1, coarse.data(), coarse.children());
            require(
                    Arrays.equals(fineBefore, read(remote, 0, -1, 1, -1).data()),
                    "coarse replacement erased client-only descendants");
            assertAncestors(reference, remote);
            if (net.fabricmc.loader.api.FabricLoader.getInstance().getEnvironmentType()
                    == net.fabricmc.api.EnvType.CLIENT) routerEvents(remote, waterValue);
            System.out.println(
                    "PASS NativeHierarchyTest: original Voxy ocean/stone mip parity LOD0-4,"
                            + " negative/adjacent nodes, parent topology, neighbor events, air"
                            + " replacement and fine-cache preservation (no GPU visual test)");
        } finally {
            reference.free();
            remote.free();
        }
    }

    private static void nativeInsert(WorldEngine world, int x, int y, int z, long[] data) {
        for (int oy = 0; oy < 2; oy++)
            for (int oz = 0; oz < 2; oz++)
                for (int ox = 0; ox < 2; ox++) {
                    var piece =
                            VoxelizedSection.createEmpty()
                                    .setPosition(x * 2 + ox, y * 2 + oy, z * 2 + oz);
                    int count = 0;
                    for (int py = 0; py < 16; py++)
                        for (int pz = 0; pz < 16; pz++)
                            for (int px = 0; px < 16; px++) {
                                long value =
                                        data[
                                                (ox * 16 + px)
                                                        | ((oz * 16 + pz) << 5)
                                                        | ((oy * 16 + py) << 10)];
                                piece.section[px | (pz << 4) | (py << 8)] = value;
                                if (!Mapper.isAir(value)) count++;
                            }
                    piece.lvl0NonAirCount = count;
                    WorldVoxilizedSectionMipper.mipSection(piece, world.getMapper());
                    WorldUpdater.insertUpdate(world, piece);
                }
    }

    private static void install(
            WorldEngine world, int level, int x, int y, int z, long[] data, byte children) {
        var mapping = new LinkedHashMap<Long, Short>();
        short[] indices = new short[32768];
        for (int i = 0; i < data.length; i++) {
            short slot = mapping.computeIfAbsent(data[i], ignored -> (short) mapping.size());
            indices[i] = slot;
        }
        long[] palette = new long[mapping.size()];
        mapping.forEach((value, slot) -> palette[slot & 65535] = value);
        NativeNodeInstaller.install(
                world, WorldEngine.getWorldSectionId(level, x, y, z), palette, indices, children);
    }

    private static SectionDataAccess.Snapshot read(
            WorldEngine world, int level, int x, int y, int z) {
        var section =
                SectionDataAccess.acquireStored(
                        world, WorldEngine.getWorldSectionId(level, x, y, z));
        require(section != null, "expected hierarchy section missing");
        try {
            return SectionDataAccess.snapshot(world, section);
        } finally {
            section.release();
        }
    }

    private static void assertAncestors(WorldEngine a, WorldEngine b) {
        for (int level = 0; level <= 4; level++)
            for (int x = -1; x <= 1; x++)
                for (int z = -1; z <= 0; z++) {
                    if (level == 0 && x == 1 && z == -1) continue;
                    int y = 1 >> level;
                    int nx = x >> level, nz = z >> level;
                    var expected = read(a, level, nx, y, nz);
                    var actual = read(b, level, nx, y, nz);
                    require(
                            Arrays.equals(expected.data(), actual.data()),
                            "native parent mip mismatch at "
                                    + WorldEngine.pprintPos(
                                            WorldEngine.getWorldSectionId(level, nx, y, nz)));
                    require(
                            expected.children() == actual.children(),
                            "native child topology mismatch");
                }
    }

    private static void routerEvents(WorldEngine world, long water) {
        var router = new me.cortex.voxy.client.core.rendering.SectionUpdateRouter();
        var meshes = new HashSet<Long>();
        var topology = new HashSet<Long>();
        router.setCallbacks(ignored -> {}, meshes::add, s -> topology.add(s.key));
        for (int level = 0; level <= 4; level++)
            router.watch(
                    WorldEngine.getWorldSectionId(level, 2 >> level, 1 >> level, 0),
                    WorldEngine.DEFAULT_UPDATE_FLAGS);
        world.setDirtyCallback((s, flags, neighbors) -> router.forwardEvent(s, flags));
        long[] data = new long[32768];
        Arrays.fill(data, water);
        install(world, 0, 2, 1, 0, data, (byte) 255);
        for (int level = 0; level <= 4; level++)
            require(
                    meshes.contains(
                            WorldEngine.getWorldSectionId(level, 2 >> level, 1 >> level, 0)),
                    "original Voxy router did not request ancestor mesh");
        require(!topology.isEmpty(), "original Voxy router received no topology updates");
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
