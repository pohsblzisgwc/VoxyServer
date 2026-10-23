package com.dripps.voxyserver.client.service;

import com.dripps.voxyserver.client.ClientLodHashStore;
import com.dripps.voxyserver.client.ClientLodSettings;
import com.dripps.voxyserver.util.SemanticSectionHash;
import com.dripps.voxyserver.util.SemanticPalette;
import com.dripps.voxyserver.network.LODBatchResultPayload;
import com.dripps.voxyserver.network.LODBulkPayload;
import com.dripps.voxyserver.network.LODSectionPayload;
import com.dripps.voxyserver.network.PreSerializedLodPayload;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.voxelization.WorldVoxilizedSectionMipper;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldUpdater;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class RemoteIngestService {
    private static final int WORKERS = 1;
    private static final int MAX_QUEUED_BATCHES = 64;

    private final ThreadPoolExecutor executor;
    private final AtomicLong generation = new AtomicLong();
    private volatile Identifier activeDimension;
    private volatile long activeEpoch;
    private volatile boolean live = true;

    private final class IngestTask implements Runnable {
        private final WorldIdentifier worldId;
        private final Identifier dimension;
        private final PreSerializedLodPayload raw;
        private final RegistryAccess registryAccess;
        private final String hashStoreKey;
        private final long taskGeneration;
        private final Consumer<LODBatchResultPayload> completion;
        private final AtomicBoolean completed = new AtomicBoolean();

        private IngestTask(WorldIdentifier worldId, Identifier dimension,
                           PreSerializedLodPayload raw, RegistryAccess registryAccess,
                           long taskGeneration, Consumer<LODBatchResultPayload> completion) {
            this.worldId = worldId;
            this.hashStoreKey = ClientLodSettings.hashStoreKey(worldId.getWorldId());
            this.dimension = dimension;
            this.raw = raw;
            this.registryAccess = registryAccess;
            this.taskGeneration = taskGeneration;
            this.completion = completion;
        }

        @Override
        public void run() {
            int status = LODBatchResultPayload.FAILURE;
            try {
                status = process(this);
            } catch (Throwable throwable) {
                Logger.error("remote ingest failed", throwable);
            } finally {
                finish(status);
            }
        }

        private void cancel() {
            finish(LODBatchResultPayload.STALE);
        }

        private void finish(int status) {
            if (completed.compareAndSet(false, true)) {
                completion.accept(new LODBatchResultPayload(raw.streamEpoch(), raw.batchId(), status));
            }
        }
    }

    public RemoteIngestService(ServiceManager ignored) {
        executor = new ThreadPoolExecutor(
                WORKERS,
                WORKERS,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(MAX_QUEUED_BATCHES),
                runnable -> {
                    Thread thread = new Thread(runnable, "VoxyServer Remote Ingest");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    public boolean enqueueIngest(WorldIdentifier worldId, Identifier dimension,
                                 PreSerializedLodPayload raw, RegistryAccess registryAccess,
                                 Consumer<LODBatchResultPayload> completion) {
        if (!live || raw.streamEpoch() != activeEpoch || !Objects.equals(dimension, activeDimension)) {
            return false;
        }
        IngestTask task = new IngestTask(
                worldId, dimension, raw, registryAccess, generation.get(), completion);
        try {
            executor.execute(task);
            return true;
        } catch (RejectedExecutionException ignored) {
            return false;
        }
    }

    public void startSession(Identifier dimension, long epoch) {
        if (activeEpoch == epoch && Objects.equals(activeDimension, dimension)) return;
        activeDimension = dimension;
        activeEpoch = epoch;
        generation.incrementAndGet();
        Runnable queued;
        while ((queued = executor.getQueue().poll()) != null) {
            if (queued instanceof IngestTask task) task.cancel();
        }
    }

    public boolean isLive() {
        return live && !executor.isShutdown();
    }

    public void shutdown() {
        live = false;
        activeDimension = null;
        activeEpoch = 0L;
        generation.incrementAndGet();
        Runnable queued;
        while ((queued = executor.getQueue().poll()) != null) {
            if (queued instanceof IngestTask task) task.cancel();
        }
        for (Runnable canceled : executor.shutdownNow()) {
            if (canceled instanceof IngestTask task) task.cancel();
        }
        try {
            executor.awaitTermination(5L, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        ClientLodHashStore.get().flush();
    }

    private int process(IngestTask task) {
        if (!isCurrent(task)) return LODBatchResultPayload.STALE;
        LODBulkPayload bulk = task.raw.decodeBulk(task.registryAccess);
        if (bulk.sections().size() != task.raw.sectionCount()
                || !task.dimension.equals(bulk.dimension())) return LODBatchResultPayload.FAILURE;
        if (!isCurrent(task)) return LODBatchResultPayload.STALE;

        WorldEngine engine = task.worldId.getOrCreateEngine();
        if (engine == null || !engine.isLive()) return LODBatchResultPayload.FAILURE;
        engine.acquireRef();
        try {
            engine.markActive();
            Registry<Biome> semanticBiomes = task.registryAccess.lookupOrThrow(Registries.BIOME);
            for (LODSectionPayload section : bulk.sections()) {
                long actual = SemanticSectionHash.compute(SemanticPalette.resolve(section.lutBlockStateIds(),
                        section.lutBiomeIds(), section.lutLight(), semanticBiomes), section.indexArray());
                if (actual != section.contentHash()) return LODBatchResultPayload.FAILURE;
            }
            Mapper mapper = engine.getMapper();
            long[] keys = new long[bulk.sections().size()];
            long[] hashes = new long[bulk.sections().size()];

            for (int sectionIndex = 0; sectionIndex < bulk.sections().size(); sectionIndex++) {
                if (!isCurrent(task)) return LODBatchResultPayload.STALE;
                LODSectionPayload section = bulk.sections().get(sectionIndex);
                if (!task.dimension.equals(section.dimension())) return LODBatchResultPayload.FAILURE;
                long[] remappedLut = remapLut(
                        section.lutBlockStateIds(),
                        section.lutBiomeIds(),
                        section.lutLight(),
                        mapper,
                        task.registryAccess
                );

                int secX = WorldEngine.getX(section.sectionKey());
                int secY = WorldEngine.getY(section.sectionKey());
                int secZ = WorldEngine.getZ(section.sectionKey());
                short[] indexArray = section.indexArray();

                for (int oy = 0; oy < 2; oy++) {
                    for (int oz = 0; oz < 2; oz++) {
                        for (int ox = 0; ox < 2; ox++) {
                            VoxelizedSection voxelized = VoxelizedSection.createEmpty();
                            voxelized.setPosition(secX * 2 + ox, secY * 2 + oy, secZ * 2 + oz);
                            int nonAirCount = 0;
                            for (int vy = 0; vy < 16; vy++) {
                                for (int vz = 0; vz < 16; vz++) {
                                    for (int vx = 0; vx < 16; vx++) {
                                        int sourceIndex = ((oy * 16 + vy) << 10)
                                                | ((oz * 16 + vz) << 5) | (ox * 16 + vx);
                                        int targetIndex = (vy << 8) | (vz << 4) | vx;
                                        int lutIndex = indexArray[sourceIndex] & 0xFFFF;
                                        if (lutIndex >= remappedLut.length) return LODBatchResultPayload.FAILURE;
                                        long id = remappedLut[lutIndex];
                                        voxelized.section[targetIndex] = id;
                                        if (!Mapper.isAir(id)) nonAirCount++;
                                    }
                                }
                            }
                            voxelized.lvl0NonAirCount = nonAirCount;
                            WorldVoxilizedSectionMipper.mipSection(voxelized, mapper);
                            WorldUpdater.insertUpdate(engine, voxelized);
                        }
                    }
                }
                keys[sectionIndex] = section.sectionKey();
                hashes[sectionIndex] = section.contentHash();
            }

            if (!isCurrent(task)) return LODBatchResultPayload.STALE;
            if (!ClientLodHashStore.get().recordBatch(task.hashStoreKey, keys, hashes)) {
                return LODBatchResultPayload.FAILURE;
            }
            return LODBatchResultPayload.SUCCESS;
        } finally {
            engine.releaseRef();
        }
    }

    private boolean isCurrent(IngestTask task) {
        return live
                && task.taskGeneration == generation.get()
                && task.raw.streamEpoch() == activeEpoch
                && Objects.equals(task.dimension, activeDimension);
    }

    private static long[] remapLut(int[] blockStateIds, int[] biomeIds, byte[] light,
                                   Mapper mapper, RegistryAccess registryAccess) {
        if (blockStateIds.length == 0 || blockStateIds.length != biomeIds.length
                || blockStateIds.length != light.length) {
            throw new IllegalArgumentException("invalid lookup table");
        }
        Registry<Biome> biomeRegistry = registryAccess.lookupOrThrow(Registries.BIOME);
        long[] remapped = new long[blockStateIds.length];
        for (int i = 0; i < blockStateIds.length; i++) {
            BlockState state = Block.BLOCK_STATE_REGISTRY.byId(blockStateIds[i]);
            int clientBlockId = state != null ? mapper.getIdForBlockState(state) : 0;
            Optional<Holder.Reference<Biome>> biomeHolder = biomeRegistry.get(biomeIds[i]);
            int clientBiomeId = biomeHolder.map(mapper::getIdForBiome).orElse(0);
            remapped[i] = Mapper.composeMappingId(light[i], clientBlockId, clientBiomeId);
        }
        return remapped;
    }
}
