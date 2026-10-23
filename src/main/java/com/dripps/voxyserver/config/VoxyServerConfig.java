package com.dripps.voxyserver.config;

import com.dripps.voxyserver.Voxyserver;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class VoxyServerConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "voxyserver.json";

    public int maxClientVoxyRadius = 1024;
    public int lodStreamRadius = 1024;
    public int maxSectionsPerTickPerPlayer = 100;
    public int sectionsPerPacket = 50;
    public boolean generateOnChunkLoad = true;
    public boolean generateOnChunkUnload = true;
    public int tickInterval = 5;
    public int workerThreads = 3;
    public boolean dirtyTrackingEnabled = true;
    public int dirtyTrackingInterval = 40;
    public boolean debugTrackingEnabled = false;
    public int debugTrackingInterval = 200;
    public boolean hashSyncEnabled = true;
    public int hashWorkIntervalTicks = 20;
    public int maxHashComputationsPerInterval = 64;
    public int maxHashMillisPerInterval = 10;
    public int hashRecheckIntervalTicks = 1200;
    public int manifestRefreshIntervalTicks = 600;
    public int maxBandwidthBytesPerSec = 250_000;
    public boolean enableDistancePacing = true;
    public boolean enableRegionHashSync = false;

    public static Path getConfigPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    public List<String> validate() {
        List<String> errors = new ArrayList<>();
        if (maxClientVoxyRadius < 1 || maxClientVoxyRadius > 4096) {
            errors.add("maxClientVoxyRadius must be between 1 and 4096, got " + maxClientVoxyRadius);
        }
        if (lodStreamRadius < 1 || lodStreamRadius > 4096) errors.add("lodStreamRadius must be 1..4096, got " + lodStreamRadius);
        if (maxSectionsPerTickPerPlayer < 1) errors.add("maxsectionspertickperplayer must be at least 1, got " + maxSectionsPerTickPerPlayer);
        if (sectionsPerPacket < 1) errors.add("sectionsperpacket must be at least 1, got " + sectionsPerPacket);
        if (tickInterval < 1) errors.add("tickinterval must be at least 1, got " + tickInterval);
        if (workerThreads < 1 || workerThreads > 32) errors.add("workerthreads must be between 1 and 32, got " + workerThreads);
        if (dirtyTrackingInterval < 1) errors.add("dirtytrackinginterval must be at least 1, got " + dirtyTrackingInterval);
        if (debugTrackingInterval < 1) errors.add("debugtrackinginterval must be at least 1, got " + debugTrackingInterval);
        if (maxBandwidthBytesPerSec < 0) errors.add("maxbandwidthbytespersec cannot be negative, got " + maxBandwidthBytesPerSec);
        if (hashWorkIntervalTicks < 1 || hashWorkIntervalTicks > 1200) errors.add("hashWorkIntervalTicks must be 1..1200, got " + hashWorkIntervalTicks);
        if (maxHashComputationsPerInterval < 1 || maxHashComputationsPerInterval > 4096) errors.add("maxHashComputationsPerInterval must be 1..4096, got " + maxHashComputationsPerInterval);
        if (maxHashMillisPerInterval < 1 || maxHashMillisPerInterval > 1000) errors.add("maxHashMillisPerInterval must be 1..1000, got " + maxHashMillisPerInterval);
        if (hashRecheckIntervalTicks < hashWorkIntervalTicks) errors.add("hashRecheckIntervalTicks must be >= hashWorkIntervalTicks, got " + hashRecheckIntervalTicks);
        if (manifestRefreshIntervalTicks < 100) errors.add("manifestRefreshIntervalTicks must be >= 100, got " + manifestRefreshIntervalTicks);
        return errors;
    }

    /** Shared by startup, reload and command application; never normalize an invalid value away. */
    public void requireValid() {
        List<String> errors = validate();
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("; ", errors));
    }

    private static VoxyServerConfig readValidated(Path path) {
        try {
            VoxyServerConfig candidate = GSON.fromJson(Files.readString(path), VoxyServerConfig.class);
            if (candidate == null) throw new IllegalArgumentException("configuration is null");
            candidate.requireValid();
            if (candidate.maxClientVoxyRadius != 1024) candidate.lodStreamRadius = candidate.maxClientVoxyRadius;
            else if (candidate.lodStreamRadius != 1024) candidate.maxClientVoxyRadius = candidate.lodStreamRadius;
            candidate.requireValid();
            return candidate;
        } catch (Exception e) {
            Voxyserver.LOGGER.warn("config rejected at {}: {}", path, e.getMessage());
            return null;
        }
    }

    public static VoxyServerConfig reload() { return reload(getConfigPath()); }
    public static VoxyServerConfig reload(Path path) { return readValidated(path); }
    public static VoxyServerConfig load() { return load(getConfigPath()); }
    public static VoxyServerConfig load(Path path) {
        if (Files.exists(path)) {
            VoxyServerConfig candidate = readValidated(path);
            if (candidate != null) return candidate;
            Voxyserver.LOGGER.warn("using validated defaults; invalid config at {} is preserved for repair", path);
        }
        VoxyServerConfig defaults = new VoxyServerConfig();
        defaults.requireValid();
        if (!Files.exists(path)) defaults.save(path);
        return defaults;
    }

    public VoxyServerConfig copy() {
        return GSON.fromJson(GSON.toJson(this), VoxyServerConfig.class);
    }

    public void save() {
        save(getConfigPath());
    }

    public void save(Path configPath) {
        requireValid();
        try {
            Files.createDirectories(configPath.getParent());
            Files.writeString(configPath, GSON.toJson(this));
        } catch (IOException e) {
            Voxyserver.LOGGER.warn("failed to save config", e);
        }
    }
}
