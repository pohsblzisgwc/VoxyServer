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

    // Upstream UnifiedServiceThreadPool.shutdown releases 10,000 worker permits.
    public static final int MAX_NATIVE_WORKER_THREADS = 10_000;

    public boolean enabled = true;
    public int maxClientVoxyRadius = 1024;
    public int lodStreamRadius = 1024;
    public int maxSectionsPerTickPerPlayer = 100;
    public int sectionsPerPacket = 16;
    public boolean generateOnChunkLoad = true;
    public boolean generateOnChunkUnload = true;
    public int workerThreads = 3;
    public boolean dirtyTrackingEnabled = true;
    public boolean debugTrackingEnabled = false;
    public int debugTrackingInterval = 200;
    public int maintenanceMillisPerSecond = 20;
    public int maintenanceQuietMillis = 5000;
    public int maintenanceCooldownMillis = 30_000;
    public int maintenanceCooldownJitterMillis = 30_000;
    public int sourceColumnRecheckSeconds = 180;
    public int maxBandwidthBytesPerSec = 250_000;

    public static Path getConfigPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    public List<String> validate() {
        List<String> errors = new ArrayList<>();
        // These view bounds are part of the existing protocol, not workload policy limits.
        if (maxClientVoxyRadius < 1 || maxClientVoxyRadius > 4096)
            errors.add(
                    "maxClientVoxyRadius must be 1..4096 (protocol bound), got "
                            + maxClientVoxyRadius);
        if (lodStreamRadius < 1 || lodStreamRadius > 4096)
            errors.add("lodStreamRadius must be 1..4096 (protocol bound), got " + lodStreamRadius);
        positive(errors, "maxSectionsPerTickPerPlayer", maxSectionsPerTickPerPlayer);
        positive(errors, "sectionsPerPacket", sectionsPerPacket);
        if (workerThreads < 1 || workerThreads > MAX_NATIVE_WORKER_THREADS)
            errors.add(
                    "workerThreads must be 1.."
                            + MAX_NATIVE_WORKER_THREADS
                            + " (native pool shutdown bound), got "
                            + workerThreads);
        positive(errors, "debugTrackingInterval", debugTrackingInterval);
        nonnegative(errors, "maxBandwidthBytesPerSec", maxBandwidthBytesPerSec);
        positive(errors, "maintenanceMillisPerSecond", maintenanceMillisPerSecond);
        nonnegative(errors, "maintenanceQuietMillis", maintenanceQuietMillis);
        nonnegative(errors, "maintenanceCooldownMillis", maintenanceCooldownMillis);
        nonnegative(errors, "maintenanceCooldownJitterMillis", maintenanceCooldownJitterMillis);
        nonnegative(errors, "sourceColumnRecheckSeconds", sourceColumnRecheckSeconds);
        return errors;
    }

    private static void positive(List<String> errors, String field, int value) {
        if (value < 1) errors.add(field + " must be 1..2147483647, got " + value);
    }

    private static void nonnegative(List<String> errors, String field, int value) {
        if (value < 0) errors.add(field + " must be 0..2147483647, got " + value);
    }

    /** Shared by startup, reload and command application; never normalize an invalid value away. */
    public void requireValid() {
        List<String> errors = validate();
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("; ", errors));
    }

    private static VoxyServerConfig readValidated(Path path) {
        try {
            VoxyServerConfig candidate =
                    GSON.fromJson(Files.readString(path), VoxyServerConfig.class);
            if (candidate == null) throw new IllegalArgumentException("configuration is null");
            if (Files.readString(path).contains("backgroundGeneration"))
                Voxyserver.LOGGER.warn(
                        "backgroundGeneration settings are obsolete and ignored; automatic chunk"
                            + " loading has been removed");
            candidate.requireValid();
            if (candidate.maxClientVoxyRadius != 1024)
                candidate.lodStreamRadius = candidate.maxClientVoxyRadius;
            else if (candidate.lodStreamRadius != 1024)
                candidate.maxClientVoxyRadius = candidate.lodStreamRadius;
            candidate.requireValid();
            return candidate;
        } catch (Exception e) {
            Voxyserver.LOGGER.warn("config rejected at {}: {}", path, e.getMessage());
            return null;
        }
    }

    public static VoxyServerConfig reload() {
        return reload(getConfigPath());
    }

    public static VoxyServerConfig reload(Path path) {
        return readValidated(path);
    }

    public static VoxyServerConfig load() {
        return load(getConfigPath());
    }

    public static VoxyServerConfig load(Path path) {
        if (Files.exists(path)) {
            VoxyServerConfig candidate = readValidated(path);
            if (candidate != null) return candidate;
            Voxyserver.LOGGER.warn(
                    "using validated defaults; invalid config at {} is preserved for repair", path);
        }
        VoxyServerConfig defaults = new VoxyServerConfig();
        defaults.requireValid();
        if (!Files.exists(path)) defaults.save(path);
        return defaults;
    }

    public boolean sameStreamingSettings(VoxyServerConfig other) {
        if (other == null) return false;
        try {
            for (var field : VoxyServerConfig.class.getFields())
                if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())
                        && !java.util.Objects.equals(field.get(this), field.get(other)))
                    return false;
            return true;
        } catch (IllegalAccessException error) {
            throw new IllegalStateException(error);
        }
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
