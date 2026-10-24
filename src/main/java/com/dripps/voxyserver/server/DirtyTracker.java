package com.dripps.voxyserver.server;

import net.minecraft.server.level.ServerLevel;

/** Constant-size bitmap marking only: no per-block hashing, snapshot copying or index updates. */
public final class DirtyTracker {
    public static volatile DirtyTracker INSTANCE;
    private final LodStreamingService streaming;

    public DirtyTracker(LodStreamingService streaming) {
        this.streaming = streaming;
    }

    public void markDirty(ServerLevel level, int chunkX, int blockY, int chunkZ) {
        streaming.markSourceDirty(level, chunkX << 4, blockY, chunkZ << 4);
    }
}
