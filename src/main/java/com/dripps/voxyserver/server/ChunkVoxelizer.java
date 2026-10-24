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

    public static void registerHandlers(java.util.function.Supplier<ChunkVoxelizer> active) {
        ServerChunkEvents.CHUNK_LOAD.register(
                (level, chunk, unused) -> {
                    var current = active.get();
                    if (current != null && current.config.generateOnChunkLoad)
                        current.mark(level, chunk);
                });
        ServerChunkEvents.CHUNK_UNLOAD.register(
                (level, chunk) -> {
                    var current = active.get();
                    if (current != null
                            && (current.config.generateOnChunkUnload
                                    || current.config.dirtyTrackingEnabled))
                        current.mark(level, chunk);
                });
    }

    private void mark(ServerLevel level, LevelChunk chunk) {
        streaming.markSourceColumn(level, chunk);
    }

    public void shutdown() {}
}
