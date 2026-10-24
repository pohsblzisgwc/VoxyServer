package com.dripps.voxyserver.server;

import com.dripps.voxyserver.Voxyserver;

import me.cortex.voxy.common.StorageConfigUtil;
import me.cortex.voxy.common.config.ConfigBuildCtx;
import me.cortex.voxy.common.config.section.SectionSerializationStorage;
import me.cortex.voxy.common.config.section.SectionStorage;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.VoxyInstance;
import me.cortex.voxy.commonImpl.WorldIdentifier;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ServerLodEngine extends VoxyInstance {
    @FunctionalInterface
    public interface DirtySectionListener {
        void onSectionDirty(Identifier dimension, long sectionKey);
    }

    private final Path basePath;
    private final java.util.UUID worldIdentity;
    private final com.dripps.voxyserver.sync.version.VersionIndex versions;

    public com.dripps.voxyserver.sync.version.VersionIndex versions() {
        return versions;
    }

    public java.util.UUID getWorldIdentity() {
        return worldIdentity;
    }

    private final SectionSerializationStorage.Config storageConfig;
    private final ConcurrentHashMap<WorldIdentifier, Identifier> dimensionsByWorld =
            new ConcurrentHashMap<>();
    private volatile DirtySectionListener dirtySectionListener;
    private final Set<WorldEngine> attached =
            Collections.newSetFromMap(new java.util.WeakHashMap<>());

    public ServerLodEngine(Path worldFolder) {
        super();
        try {
            this.basePath = worldFolder.resolve("voxyserver");
            try {
                this.worldIdentity =
                        com.dripps.voxyserver.util.WorldIdentity.loadOrCreate(
                                this.basePath.resolve("world-id"));
            } catch (java.io.IOException failure) {
                throw new IllegalStateException("Cannot establish LOD cache identity", failure);
            }
            try {
                this.versions =
                        new com.dripps.voxyserver.sync.version.VersionIndex(
                                basePath.resolve("lod0-versions-v6.bin"));
            } catch (java.io.IOException failure) {
                throw new IllegalStateException("Cannot open LOD version index", failure);
            }
            this.storageConfig = StorageConfigUtil.createDefaultSerializer();
            this.updateDedicatedThreads();
            Voxyserver.LOGGER.info("server lod engine started, storage at {}", this.basePath);
        } catch (RuntimeException | Error failure) {
            try {
                super.shutdown();
            } catch (RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    public void updateDedicatedThreadsCount(int threads) {
        this.setNumThreads(threads);
    }

    public void setDirtySectionListener(DirtySectionListener dirtySectionListener) {
        this.dirtySectionListener = dirtySectionListener;
    }

    public WorldEngine getOrCreate(ServerLevel level) {
        WorldIdentifier worldId = WorldIdentifier.of(level);
        if (worldId == null) {
            return null;
        }
        return this.getOrCreate(worldId, level.dimension().identifier());
    }

    public WorldEngine getOrCreate(WorldIdentifier identifier, Identifier dimension) {
        if (identifier == null || !this.isRunning()) {
            return null;
        }
        this.dimensionsByWorld.put(identifier, dimension);
        WorldEngine world;
        try {
            world = super.getOrCreate(identifier);
        } catch (Exception e) {
            Voxyserver.LOGGER.error(
                    "couldnt get or create world for {}, this is prolly a leaked lock in"
                            + " VoxyInstance",
                    identifier,
                    e);
            return null;
        }
        if (world == null) {
            return null;
        }
        this.attachDirtyCallback(identifier, world);
        Identifier dimensionId = dimensionsByWorld.get(identifier);
        if (dimensionId != null)
            versions.bootstrap(
                    dimensionId, consumer -> world.storage.iteratePositions(0, consumer));
        return world;
    }

    @Override
    public WorldEngine getOrCreate(WorldIdentifier identifier) {
        if (!this.isRunning()) {
            return null;
        }
        WorldEngine world;
        try {
            world = super.getOrCreate(identifier);
        } catch (Exception e) {
            Voxyserver.LOGGER.error(
                    "couldnt get or create world for {}, this is prolly a leaked lock in"
                            + " VoxyInstance",
                    identifier,
                    e);
            return null;
        }
        if (world == null) {
            return null;
        }
        this.attachDirtyCallback(identifier, world);
        Identifier dimensionId = dimensionsByWorld.get(identifier);
        if (dimensionId != null)
            versions.bootstrap(
                    dimensionId, consumer -> world.storage.iteratePositions(0, consumer));
        return world;
    }

    Path maintenanceJournal() {
        return basePath.resolve("maintenance-pending-v5.bin");
    }

    @Override
    public void shutdown() {
        super.shutdown();
        try {
            versions.markClean();
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Cannot checkpoint LOD versions", failure);
        }
    }

    @Override
    protected SectionStorage createStorage(WorldIdentifier identifier) {
        if (com.dripps.voxyserver.util.ServerStatsTracker.INSTANCE != null) {
            com.dripps.voxyserver.util.ServerStatsTracker.INSTANCE.markEngineAction();
        }
        var ctx = new ConfigBuildCtx();
        ctx.setProperty(ConfigBuildCtx.BASE_SAVE_PATH, this.basePath.toString());
        ctx.setProperty(ConfigBuildCtx.WORLD_IDENTIFIER, identifier.getWorldId());
        ctx.pushPath(ConfigBuildCtx.DEFAULT_STORAGE_PATH);
        return new ImportSectionStorage(this.storageConfig.build(ctx));
    }

    Path importCheckpointPath(WorldIdentifier identifier) {
        return this.basePath
                .resolve(identifier.getWorldId())
                .resolve("storage")
                .resolve("import-checkpoints");
    }

    private void attachDirtyCallback(WorldIdentifier identifier, WorldEngine world) {
        if (world == null) {
            return;
        }

        Identifier dimension = this.dimensionsByWorld.get(identifier);
        if (dimension == null) {
            return;
        }

        synchronized (attached) {
            if (!attached.add(world)) return;
            world.setDirtyCallback(
                    (section, updateFlags, neighborMsk) -> {
                        if (world.storage instanceof ImportSectionStorage storage)
                            storage.capture(section);
                        versions.changed(dimension, section.key);
                        DirtySectionListener listener = this.dirtySectionListener;
                        if (listener != null) {
                            listener.onSectionDirty(dimension, section.key);
                        }
                    });
        }
    }
}
