package com.dripps.voxyserver.server;

import com.dripps.voxyserver.Voxyserver;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.voxelization.WorldConversionFactory;
import me.cortex.voxy.common.voxelization.WorldVoxilizedSectionMipper;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldUpdater;
import me.cortex.voxy.commonImpl.importers.IDataImporter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;

final class ResumableWorldImporter {
    private final WorldEngine world;
    private final ServerLevel level;
    private final Path directory;
    private final RegionImportCheckpoint checkpoints;
    private final boolean force;
    private final PalettedContainerFactory palettes;
    private volatile boolean cancelled;
    private volatile boolean successful;
    private volatile Thread worker;
    private volatile int skippedRegions;
    private volatile int completedRegions;
    private volatile int totalRegions;
    private volatile int changedRegions;
    private volatile int failedRegions;
    private volatile String failure;
    private int chunks;

    ResumableWorldImporter(WorldEngine world, ServerLevel level, Path directory, Path checkpointPath, boolean force) {
        this.world = world;
        this.level = level;
        this.directory = directory;
        this.checkpoints = new RegionImportCheckpoint(checkpointPath);
        this.force = force;
        this.palettes = PalettedContainerFactory.create(level.registryAccess());
    }

    synchronized void runImport(IDataImporter.IUpdateCallback update, IDataImporter.ICompletionCallback completion) {
        if (worker != null) throw new IllegalStateException("Import already started");
        world.acquireRef();
        worker = new Thread(() -> {
            try {
                run(update);
                successful = !cancelled && changedRegions == 0 && failedRegions == 0;
            } catch (CancellationException ignored) {
            } catch (Exception e) {
                failure = e.getMessage();
                Voxyserver.LOGGER.error("Region import failed for {}", directory, e);
            } finally {
                world.releaseRef();
                completion.onCompletion(chunks);
            }
        }, "VoxyServer Region Import");
        worker.start();
    }

    private void run(IDataImporter.IUpdateCallback update) throws IOException {
        List<Path> regions;
        try (var paths = Files.list(directory)) {
            regions = paths.filter(path -> path.getFileName().toString().matches("r\\.-?\\d+\\.-?\\d+\\.mca"))
                    .sorted().toList();
        }
        totalRegions = regions.size();
        if (force) {
            for (Path region : regions) {
                checkCancelled();
                checkpoints.invalidate(region);
            }
        }
        var storage = (ImportSectionStorage) world.storage;
        for (Path region : regions) {
            checkCancelled();
            if (checkpoints.matches(region)) {
                skippedRegions++;
                continue;
            }
            checkpoints.invalidate(region);
            Map<String, RegionImportCheckpoint.Fingerprint> dependencies = new LinkedHashMap<>();
            storage.beginImport();
            try {
                try {
                    dependencies.put(region.getFileName().toString(), RegionImportCheckpoint.Fingerprint.read(region));
                    importRegion(region, dependencies, update, storage);
                } catch (CancellationException e) {
                    throw e;
                } catch (IOException | RuntimeException e) {
                    failedRegions++;
                    Voxyserver.LOGGER.warn("Skipping unfinished region {}; continuing import", region, e);
                    continue;
                }
                checkCancelled();
                storage.saveImport(true);
                storage.flush();
                checkCancelled();
                if (RegionImportCheckpoint.unchanged(directory, dependencies)) {
                    checkpoints.complete(region, dependencies);
                    completedRegions++;
                } else {
                    changedRegions++;
                    Voxyserver.LOGGER.warn("Region changed during import; leaving it unfinished: {}", region);
                }
            } finally {
                storage.endImport();
            }
        }
    }

    private void importRegion(Path region, Map<String, RegionImportCheckpoint.Fingerprint> dependencies,
                              IDataImporter.IUpdateCallback update, ImportSectionStorage storage) throws IOException {
        String[] parts = region.getFileName().toString().split("\\.");
        int regionX = Integer.parseInt(parts[1]);
        int regionZ = Integer.parseInt(parts[2]);
        try (var file = new RandomAccessFile(region.toFile(), "r")) {
            if (file.length() < 8192) throw new IOException("Truncated region header: " + region);
            int[] locations = new int[1024];
            for (int i = 0; i < locations.length; i++) locations[i] = file.readInt();
            for (int i = 0; i < locations.length; i++) {
                checkCancelled();
                if (locations[i] == 0) continue;
                int sector = locations[i] >>> 8;
                int sectors = locations[i] & 255;
                if (sector < 2 || sectors == 0 || (long) sector * 4096 + 5 > file.length()) {
                    throw new IOException("Invalid chunk location in " + region + " at " + i);
                }
                file.seek((long) sector * 4096);
                int length = file.readInt();
                int flags = file.readUnsignedByte();
                if (length < 1 || length > sectors * 4096 - 4
                        || (long) sector * 4096 + 4 + length > file.length()) {
                    throw new IOException("Invalid chunk length in " + region);
                }
                var version = RegionFileVersion.fromId(flags & 127);
                if (version == null) throw new IOException("Unknown chunk compression in " + region);
                int x = Math.addExact(Math.multiplyExact(regionX, 32), i & 31);
                int z = Math.addExact(Math.multiplyExact(regionZ, 32), i >> 5);
                InputStream compressed;
                if ((flags & 128) != 0) {
                    if (length != 1) throw new IOException("Invalid external chunk length in " + region);
                    String name = "c." + x + "." + z + ".mcc";
                    Path external = directory.resolve(name);
                    dependencies.put(name, RegionImportCheckpoint.Fingerprint.read(external));
                    compressed = Files.newInputStream(external);
                } else {
                    byte[] bytes = new byte[length - 1];
                    file.readFully(bytes);
                    compressed = new ByteArrayInputStream(bytes);
                }
                try (compressed; var input = new DataInputStream(version.wrap(compressed))) {
                    if (importChunk(NbtIo.read(input), x, z, storage)) {
                        chunks++;
                        update.onUpdate(chunks, chunks);
                    }
                }
            }
        }
    }

    private boolean importChunk(CompoundTag chunk, int x, int z, ImportSectionStorage storage) throws IOException {
        String statusName = chunk.getStringOr("Status", "");
        var status = ChunkStatus.byName(statusName);
        if (status == null) throw new IOException("Missing or unknown chunk status at " + x + ", " + z);
        if (status != ChunkStatus.FULL && status != ChunkStatus.EMPTY) return false;
        if (chunk.getIntOr("xPos", Integer.MIN_VALUE) != x || chunk.getIntOr("zPos", Integer.MIN_VALUE) != z) {
            throw new IOException("Chunk position mismatch at " + x + ", " + z);
        }
        if (!chunk.getBooleanOr("isLightOn", false))
            throw new IOException("Chunk lighting is incomplete at " + x + ", " + z);
        Map<Integer, CompoundTag> sections = new HashMap<>();
        Map<Integer, DataLayer> skyLayers = new HashMap<>();
        for (var tag : chunk.getList("sections").orElseThrow(() -> new IOException("Missing chunk sections"))) {
            if (!(tag instanceof CompoundTag section)) throw new IOException("Invalid chunk section");
            int y = section.getIntOr("Y", Integer.MIN_VALUE);
            if (y == Integer.MIN_VALUE || sections.put(y, section) != null) throw new IOException("Invalid section Y");
            DataLayer sky = light(section, "SkyLight");
            if (sky != null) skyLayers.put(y, sky);
        }
        for (int y = level.getMinSectionY(); y <= level.getMaxSectionY(); y++) {
            checkCancelled();
            CompoundTag section = sections.getOrDefault(y, new CompoundTag());
            var blocks = section.getCompound("block_states").isPresent()
                    ? palettes.blockStatesContainerCodec().parse(NbtOps.INSTANCE, section.getCompound("block_states").orElseThrow()).getOrThrow()
                    : palettes.createForBlockStates();
            var biomes = section.getCompound("biomes").isPresent()
                    ? palettes.biomeContainerCodec().parse(NbtOps.INSTANCE, section.getCompound("biomes").orElseThrow()).getOrThrow()
                    : palettes.createForBiomes();
            var blockLight = light(section, "BlockLight");
            var skyLight = light(section, "SkyLight");
            if (skyLight == null)
                skyLight = com.dripps.voxyserver.sync.SkyLightSnapshot.inheritAbove(
                        y, level.getMaxSectionY(), skyLayers);
            final DataLayer effectiveSkyLight = skyLight;
            var voxelized = WorldConversionFactory.convert(VoxelizedSection.createEmpty().setPosition(x, y, z),
                    world.getMapper(), blocks, biomes, (bx, by, bz) -> (byte) (
                            effectiveSkyLight.get(bx, by, bz)
                                    | ((blockLight == null ? 0 : blockLight.get(bx, by, bz)) << 4)));
            WorldVoxilizedSectionMipper.mipSection(voxelized, world.getMapper());
            for (int lod = 0; lod <= WorldEngine.MAX_LOD_LAYER; lod++) {
                var target = world.acquire(lod, x >> (lod + 1), y >> (lod + 1), z >> (lod + 1));
                try { storage.capture(target); } finally { target.release(); }
            }
            WorldUpdater.insertUpdate(world, voxelized);
            storage.saveImport(false);
        }
        return true;
    }

    private static DataLayer light(CompoundTag section, String key) {
        byte[] bytes = section.getByteArray(key).orElse(new byte[0]);
        return bytes.length == 0 ? null : new DataLayer(bytes);
    }

    private void checkCancelled() {
        if (cancelled) throw new CancellationException();
    }

    void shutdown() {
        cancelled = true;
        Thread thread = worker;
        if (thread == null || thread == Thread.currentThread()) return;
        boolean interrupted = false;
        while (thread.isAlive()) {
            try { thread.join(); } catch (InterruptedException e) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    boolean isSuccessful() { return successful; }

    String summary() {
        return completedRegions + " imported, " + skippedRegions + " skipped, " + changedRegions
                + " changed during import, " + failedRegions + " failed / " + totalRegions + " regions"
                + (failure == null ? "" : "; error: " + failure);
    }
}
