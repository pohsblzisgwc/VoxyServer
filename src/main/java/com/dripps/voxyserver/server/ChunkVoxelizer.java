package com.dripps.voxyserver.server;

import com.dripps.voxyserver.config.VoxyServerConfig;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Lifecycle events only mark bitmap cells. Palette copying/conversion is handled by
 * SourceMaintenance.
 */
public final class ChunkVoxelizer {
    private final LodStreamingService streaming;
    private final VoxyServerConfig config;

    public ChunkVoxelizer(
            ServerLodEngine engine, LodStreamingService streaming, VoxyServerConfig config) {
        this.streaming = streaming;
        this.config = config;
    }

    public void register() {
        if (config.generateOnChunkLoad)
            ServerChunkEvents.CHUNK_LOAD.register((level, chunk, unused) -> mark(level, chunk));
        ServerChunkEvents.CHUNK_UNLOAD.register(
                (level, chunk) -> {
                    // Capture the final state through the vanilla I/O worker after unload, without
                    // pinning chunks.
                    if (config.generateOnChunkUnload || config.dirtyTrackingEnabled)
                        mark(level, chunk);
                });
    }

    private void mark(ServerLevel level, LevelChunk chunk) {
        for (int y = chunk.getMinSectionY(); y < chunk.getMaxSectionY(); y++)
            streaming.markSourceDirty(
                    level, chunk.getPos().x() << 4, y << 4, chunk.getPos().z() << 4);
    }

    public void shutdown() {}
}
