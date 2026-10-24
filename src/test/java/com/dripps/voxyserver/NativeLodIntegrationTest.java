package com.dripps.voxyserver;

import com.dripps.voxyserver.network.*;
import com.dripps.voxyserver.sync.*;
import com.dripps.voxyserver.util.*;
import com.mojang.serialization.Lifecycle;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import me.cortex.voxy.common.config.section.SectionStorage;
import me.cortex.voxy.common.voxelization.*;
import me.cortex.voxy.common.world.*;
import me.cortex.voxy.common.world.other.Mapper;

import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.Blocks;

import java.nio.ByteBuffer;
import java.util.*;

/**
 * Uses actual Voxy worlds, mappers, native arrays and applied mixins, without a renderer or a saved
 * world.
 */
public final class NativeLodIntegrationTest {
    private static final Identifier DIM = Identifier.parse("minecraft:overworld");

    public static void runAllTests() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        if (net.fabricmc.loader.api.FabricLoader.getInstance().getEnvironmentType()
                == net.fabricmc.api.EnvType.SERVER)
            com.dripps.voxyserver.server.NativeRuntimeLifecycleTest.run();
        com.dripps.voxyserver.server.background.BackgroundChunkGeneration.registerTicketType();
        var backgroundTicket =
                com.dripps.voxyserver.server.background.BackgroundChunkGeneration.ticketType();
        require(
                backgroundTicket.doesLoad()
                        && !backgroundTicket.doesSimulate()
                        && !backgroundTicket.persist()
                        && !backgroundTicket.canExpireIfUnloaded()
                        && backgroundTicket.timeout() > 0,
                "background generation ticket can persist, tick entities, or expire before"
                        + " loading");
        require(
                !backgroundTicket.equals(TicketType.PLAYER_LOADING),
                "background ticket aliases player loading");
        var ticketStore = new net.minecraft.world.level.TicketStorage();
        var position = new net.minecraft.world.level.ChunkPos(1, 2);
        ticketStore.addTicketWithRadius(backgroundTicket, position, 0);
        ticketStore.addTicketWithRadius(TicketType.PLAYER_LOADING, position, 0);
        var owned =
                ticketStore.getTickets(position.pack()).stream()
                        .filter(t -> t.getType() == backgroundTicket)
                        .findFirst()
                        .orElseThrow();
        for (int i = 0; i < backgroundTicket.timeout() + 1; i++) owned.decreaseTicksLeft();
        require(owned.isTimedOut(), "native ticket expiry test not reached");
        ticketStore.addTicketWithRadius(backgroundTicket, position, 0);
        require(
                !owned.isTimedOut() && ticketStore.getTickets(position.pack()).size() == 2,
                "native refresh duplicated or failed to renew ticket");
        ticketStore.removeTicketWithRadius(backgroundTicket, position, 0);
        require(
                ticketStore.getTickets(position.pack()).size() == 1
                        && ticketStore.getTickets(position.pack()).getFirst().getType()
                                == TicketType.PLAYER_LOADING,
                "release removed player ticket");
        // Loading, rather than reading bytecode signatures, checks every required server mixin's
        // targets.
        for (String name :
                List.of(
                        "net.minecraft.world.level.chunk.LevelChunk",
                        "net.minecraft.world.level.chunk.LevelChunkSection",
                        "net.minecraft.server.level.ServerChunkCache",
                        "net.minecraft.server.level.ChunkMap",
                        "me.cortex.voxy.common.world.WorldUpdater"))
            Class.forName(name, false, NativeLodIntegrationTest.class.getClassLoader());
        var registry = biomes();
        snapshotLockSwitch(registry);
        NativeHierarchyTest.run();
        var server = new WorldEngine(new MemoryStorage());
        var client = new WorldEngine(new MemoryStorage());
        configure(server);
        configure(client);
        try {
            Mapper a = server.getMapper(), b = client.getMapper();
            int stoneA = a.getIdForBlockState(Blocks.STONE.defaultBlockState());
            int dirtB = b.getIdForBlockState(Blocks.DIRT.defaultBlockState());
            int stoneB = b.getIdForBlockState(Blocks.STONE.defaultBlockState());
            int biomeA =
                    a.getIdForBiome(
                            registry.getOrThrow(
                                    ResourceKey.create(
                                            Registries.BIOME,
                                            Identifier.parse("minecraft:plains"))));
            int biomeB =
                    b.getIdForBiome(
                            registry.getOrThrow(
                                    ResourceKey.create(
                                            Registries.BIOME,
                                            Identifier.parse("minecraft:plains"))));
            require(stoneA != stoneB, "mapper fixture did not reorder IDs");
            long stone = Mapper.composeMappingId((byte) 15, stoneA, biomeA),
                    dirt = Mapper.composeMappingId((byte) 15, dirtB, biomeB);
            long fine = WorldEngine.getWorldSectionId(0, 0, 0, 0),
                    coarse = WorldEngine.getWorldSectionId(4, 0, 0, 0);
            short[] indices = new short[32768];
            NativeNodeInstaller.install(client, fine, new long[] {dirt}, indices, (byte) 255);
            NativeNodeInstaller.install(client, coarse, new long[] {dirt}, indices, (byte) 1);
            NativeNodeInstaller.install(server, coarse, new long[] {stone}, indices, (byte) 2);
            com.dripps.voxyserver.server.DemandIndexTest.runNativeBudgetTests(
                    server, registry, coarse);
            var source = SectionDataAccess.acquireStored(server, coarse);
            require(source != null, "written source missing");
            LODSectionPayload payload;
            try {
                var snapshot = SectionDataAccess.snapshot(server, source);
                payload =
                        NativeNodeCodec.encode(
                                DIM, coarse, snapshot.data(), snapshot.children(), a, registry);
            } finally {
                source.release();
            }
            var raw =
                    PreSerializedLodPayload.fromBulk(
                                    new LODBulkPayload(DIM, List.of(payload)), RegistryAccess.EMPTY)
                            .withDemand(DemandGroup.of(coarse), 1)
                            .withDelivery(7, 1);
            var decoded = raw.decodeBulk(RegistryAccess.EMPTY).sections().getFirst();
            require(
                    decoded.sectionKey() == coarse
                            && decoded.childMask() == 2
                            && decoded.contentHash() == payload.contentHash(),
                    "native LOD/topology lost in codec");
            long before = hash(client, fine, registry, (byte) 255);
            long[] clientPalette = {Mapper.composeMappingId((byte) 15, stoneB, biomeB)};
            NativeNodeInstaller.install(
                    client, coarse, clientPalette, decoded.indexArray(), decoded.childMask());
            require(
                    hash(client, fine, registry, (byte) 255) == before,
                    "coarse replacement changed independently cached fine terrain");
            var installed = SectionDataAccess.acquireStored(client, coarse);
            try {
                require(
                        installed.getNonEmptyChildren() == 3,
                        "coarse replacement discarded client-only descendants");
            } finally {
                installed.release();
            }
            require(
                    hash(client, coarse, registry, decoded.childMask()) == payload.contentHash(),
                    "semantic proof differs across mapper IDs or client-only children");
            // Voxy itself returns a cached air placeholder on subsequent missing reads. It is never
            // replacement data.
            long absent = WorldEngine.getWorldSectionId(4, 12, 0, 12);
            for (int i = 0; i < 100; i++)
                require(
                        SectionDataAccess.acquireStored(server, absent) == null,
                        "cached missing node became fabricated all-air replacement");
            require(
                    hash(client, fine, registry, (byte) 255) == before,
                    "absence cleared client terrain");
            ((MemoryStorage) server.storage)
                    .unreadable.add(WorldEngine.getWorldSectionId(4, 13, 0, 13));
            for (int i = 0; i < 5; i++)
                require(
                        SectionDataAccess.acquireStored(
                                        server, WorldEngine.getWorldSectionId(4, 13, 0, 13))
                                == null,
                        "unreadable source became air");
            var invalid = indices.clone();
            invalid[32767] = 1;
            try {
                NativeNodeInstaller.install(client, coarse, clientPalette, invalid, (byte) 2);
                throw new AssertionError("bad final index installed");
            } catch (IllegalArgumentException expected) {
            }
            require(
                    hash(client, coarse, registry, decoded.childMask()) == payload.contentHash(),
                    "malformed node partially overwrote data");
            // Native updates really execute within the shared world monitor and preserve all native
            // levels.
            var vox = VoxelizedSection.createEmpty().setPosition(0, 0, 0);
            Arrays.fill(vox.section, 0, 4096, stone);
            vox.lvl0NonAirCount = 4096;
            WorldVoxilizedSectionMipper.mipSection(vox, a);
            WorldUpdater.insertUpdate(server, vox);
            for (int lod = 0; lod <= 4; lod++) {
                var node =
                        SectionDataAccess.acquireStored(
                                server, WorldEngine.getWorldSectionId(lod, 0, 0, 0));
                require(node != null, "native source update lost LOD " + lod);
                node.release();
            }
            System.out.println(
                    "PASS NativeLodIntegrationTest: real native mappers/LOD ingest, presence"
                            + " placeholders, source locking, overwrite-only cache and semantic"
                            + " proofs");
        } finally {
            server.free();
            client.free();
        }
    }

    private static void snapshotLockSwitch(Registry<Biome> biomes) throws Exception {
        var factory =
                net.minecraft.world.level.chunk.PalettedContainerFactory.create(
                        new RegistryAccess.ImmutableRegistryAccess(List.of(biomes)));
        var section = new net.minecraft.world.level.chunk.LevelChunkSection(factory);
        var configField = Voxyserver.class.getDeclaredField("config");
        configField.setAccessible(true);
        var saved = configField.get(null);
        try {
            for (boolean enabled : new boolean[] {true, false}) {
                // Null configuration is the pre-launch active-lock fixture; disabled config uses
                // the real fast path.
                var c = new com.dripps.voxyserver.config.VoxyServerConfig();
                c.enabled = false;
                configField.set(null, enabled ? null : c);
                var entered = new java.util.concurrent.CountDownLatch(1);
                var finished = new java.util.concurrent.CountDownLatch(1);
                var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
                var writer =
                        new Thread(
                                () -> {
                                    entered.countDown();
                                    try {
                                        section.setBlockState(
                                                0,
                                                0,
                                                0,
                                                enabled
                                                        ? Blocks.STONE.defaultBlockState()
                                                        : Blocks.DIRT.defaultBlockState(),
                                                true);
                                    } catch (Throwable error) {
                                        failure.set(error);
                                    } finally {
                                        finished.countDown();
                                    }
                                });
                try {
                    synchronized (section) {
                        writer.start();
                        require(
                                entered.await(2, java.util.concurrent.TimeUnit.SECONDS),
                                "native writer did not start");
                        if (enabled) {
                            long deadline = System.nanoTime() + 2_000_000_000L;
                            while (writer.getState() != Thread.State.BLOCKED
                                    && writer.isAlive()
                                    && System.nanoTime() < deadline) Thread.yield();
                            require(
                                    writer.getState() == Thread.State.BLOCKED,
                                    "enabled source write bypassed snapshot monitor");
                        } else
                            require(
                                    finished.await(2, java.util.concurrent.TimeUnit.SECONDS),
                                    "disabled source write still took snapshot monitor");
                    }
                } finally {
                    writer.join(3000);
                }
                require(
                        !writer.isAlive() && failure.get() == null,
                        "native snapshot lock test failed: " + failure.get());
            }
            System.out.println(
                    "PASS native global-switch writer guard: enabled writers synchronize; disabled"
                            + " writers bypass the extra monitor");
        } finally {
            configField.set(null, saved);
        }
    }

    private static long hash(
            WorldEngine world, long key, Registry<Biome> biomes, byte serverChildren) {
        var section = SectionDataAccess.acquireStored(world, key);
        require(section != null, "expected stored node");
        try {
            var data = SectionDataAccess.snapshot(world, section);
            return NativeNodeCodec.proofHash(
                    SemanticPalette.hashStored(data.data(), world.getMapper(), biomes),
                    (byte) (data.children() & serverChildren));
        } finally {
            section.release();
        }
    }

    static WorldEngine memoryWorld() {
        var world = new WorldEngine(new MemoryStorage());
        configure(world);
        return world;
    }

    private static void configure(WorldEngine world) {
        world.setDirtyCallback(
                (section, flags, neighbors) ->
                        require(Thread.holdsLock(world), "native update escaped world monitor"));
        world.setSaveCallback(
                (engine, section, nonBlocking, acquired) -> {
                    require(!nonBlocking, "unexpected concurrent fixture save");
                    engine.storage.saveSection(section);
                    section.setNotDirty();
                    if (acquired) section.release();
                    return true;
                });
    }

    private static MappedRegistry<Biome> biomes() {
        var registry = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        var biome =
                new Biome.BiomeBuilder()
                        .hasPrecipitation(true)
                        .temperature(0.8f)
                        .downfall(0.4f)
                        .specialEffects(
                                new BiomeSpecialEffects.Builder().waterColor(0x3f76e4).build())
                        .mobSpawnSettings(MobSpawnSettings.EMPTY)
                        .generationSettings(BiomeGenerationSettings.EMPTY)
                        .build();
        registry.register(
                ResourceKey.create(Registries.BIOME, Identifier.parse("minecraft:plains")),
                biome,
                RegistrationInfo.BUILT_IN);
        registry.freeze();
        return registry;
    }

    private static final class MemoryStorage extends SectionStorage {
        final Map<Long, SectionDataAccess.Snapshot> sections = new HashMap<>();
        final Set<Long> unreadable = new HashSet<>();
        final Int2ObjectOpenHashMap<byte[]> mappings = new Int2ObjectOpenHashMap<>();

        @Override
        public int loadSection(WorldSection into) {
            if (unreadable.contains(into.key)) return -1;
            var data = sections.get(into.key);
            if (data == null) return 1;
            System.arraycopy(data.data(), 0, into._unsafeGetRawDataArray(), 0, 32768);
            into._unsafeSetNonEmptyChildren(data.children());
            if (into.lvl == 0) {
                int count = 0;
                for (long value : data.data()) if (!Mapper.isAir(value)) count++;
                into.addNonEmptyBlockCount(count);
            }
            return 0;
        }

        @Override
        public void saveSection(WorldSection section) {
            sections.put(
                    section.key,
                    new SectionDataAccess.Snapshot(
                            section.copyData(), section.getNonEmptyChildren()));
        }

        @Override
        public void putIdMapping(int id, ByteBuffer data) {
            var bytes = new byte[data.remaining()];
            data.get(bytes);
            mappings.put(id, bytes);
        }

        @Override
        public Int2ObjectOpenHashMap<byte[]> getIdMappingsData() {
            return mappings;
        }

        @Override
        public void iteratePositions(int level, java.util.function.LongConsumer consumer) {
            throw new AssertionError("full database enumeration");
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
