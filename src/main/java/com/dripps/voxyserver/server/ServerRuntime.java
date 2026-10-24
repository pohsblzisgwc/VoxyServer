package com.dripps.voxyserver.server;

import com.dripps.voxyserver.config.VoxyServerConfig;
import com.dripps.voxyserver.server.background.BackgroundChunkGeneration;
import com.dripps.voxyserver.util.ServerStatsTracker;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/** All heavyweight per-world services have the same lifetime as the global enabled switch. */
public final class ServerRuntime implements AutoCloseable {
    private ServerLodEngine engine;
    private LodStreamingService streaming;
    private ChunkVoxelizer voxelizer;
    private WorldImportCoordinator imports;
    private BackgroundChunkGeneration background;
    private DirtyTracker dirty;
    private ServerStatsTracker stats;

    public ServerRuntime(MinecraftServer server, VoxyServerConfig config) {
        try {
            engine = new ServerLodEngine(server.getWorldPath(LevelResource.ROOT));
            engine.updateDedicatedThreadsCount(config.workerThreads);
            streaming = new LodStreamingService(engine, config);
            voxelizer = new ChunkVoxelizer(engine, streaming, config);
            imports = new WorldImportCoordinator(engine, streaming);
            background = new BackgroundChunkGeneration(server, streaming, config);
            if (config.dirtyTrackingEnabled)
                dirty = new DirtyTracker(voxelizer, streaming, config.dirtyTrackingInterval);
            stats = new ServerStatsTracker(config.debugTrackingInterval);
            DirtyTracker.INSTANCE = dirty;
            ServerStatsTracker.INSTANCE = stats;
            streaming.start(server);
        } catch (RuntimeException | Error failure) {
            try {
                close();
            } catch (RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    public LodStreamingService streaming() {
        return streaming;
    }

    public ChunkVoxelizer voxelizer() {
        return voxelizer;
    }

    public WorldImportCoordinator imports() {
        return imports;
    }

    public BackgroundChunkGeneration background() {
        return background;
    }

    public ServerStatsTracker stats() {
        return stats;
    }

    public void updateConfig(VoxyServerConfig previous, VoxyServerConfig next) {
        if (!next.sameStreamingSettings(previous)) streaming.updateConfig(next);
        background.updateConfig(next);
        if (dirty != null) dirty.updateFlushInterval(next.dirtyTrackingInterval);
        if (previous.workerThreads != next.workerThreads)
            engine.updateDedicatedThreadsCount(next.workerThreads);
        stats.updateTickInterval(next.debugTrackingInterval);
    }

    @Override
    public void close() {
        DirtyTracker.INSTANCE = null;
        ServerStatsTracker.INSTANCE = null;
        Throwable failure = null;
        // Run every release even if one component fails. Do not shut storage before its producers.
        for (Runnable stop :
                new Runnable[] {
                    () -> {
                        if (background != null) background.shutdown();
                    },
                    () -> {
                        if (imports != null) imports.shutdown();
                    },
                    () -> {
                        if (streaming != null) streaming.shutdown();
                    },
                    () -> {
                        if (voxelizer != null) voxelizer.shutdown();
                    },
                    () -> {
                        if (engine != null) engine.shutdown();
                    }
                }) {
            try {
                stop.run();
            } catch (Throwable error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
        }
        background = null;
        imports = null;
        streaming = null;
        voxelizer = null;
        engine = null;
        dirty = null;
        stats = null;
        if (failure instanceof Error error) throw error;
        if (failure != null) throw (RuntimeException) failure;
    }
}
