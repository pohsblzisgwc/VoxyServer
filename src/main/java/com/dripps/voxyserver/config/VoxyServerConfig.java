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

    public static final int MAX_BACKGROUND_CHUNKS_PER_MINUTE = 600_000;
    // Upstream UnifiedServiceThreadPool.shutdown releases 10,000 worker permits.
    public static final int MAX_NATIVE_WORKER_THREADS = 10_000;

    public boolean enabled = true;
    public int maxClientVoxyRadius = 1024;
    public int lodStreamRadius = 1024;
    public int maxSectionsPerTickPerPlayer = 100;
    public int sectionsPerPacket = 16;
    public boolean generateOnChunkLoad = true;
    public boolean generateOnChunkUnload = true;
    public int tickInterval = 5;
    public int workerThreads = 3;
    public boolean dirtyTrackingEnabled = true;
    public int dirtyTrackingInterval = 40;
    public boolean debugTrackingEnabled = false;
    public int debugTrackingInterval = 200;
    public boolean hashSyncEnabled = true;
    public int maintenanceMillisPerSecond = 20;
    public int maintenanceQuietMillis = 2000;
    public int maintenanceCooldownMillis = 10_000;
    public int maxDemandRequestsPerSecond = 20;
    public int hashWorkIntervalTicks = 20;
    public int maxHashComputationsPerInterval = 64;
    public int maxHashMillisPerInterval = 10;
    public int hashRecheckIntervalTicks = 1200;
    public int manifestRefreshIntervalTicks = 600;
    public int maxBandwidthBytesPerSec = 250_000;
    public boolean enableDistancePacing = true;
    public boolean enableRegionHashSync = false;

    public boolean backgroundGenerationEnabled = false;
    public int backgroundGenerationMinRadiusChunks = 16;
    public int backgroundGenerationMaxRadiusChunks = 64;
    public int backgroundGenerationChunksPerMinute = 6;
    public int backgroundGenerationMaxConcurrent = 1;
    public int backgroundGenerationCheckIntervalTicks = 100;
    public int backgroundGenerationCandidatesPerPass = 4;
    public int backgroundGenerationMaxProbeMillis = 1;
    public double backgroundGenerationStartMspt = 25.0;
    public double backgroundGenerationStopMspt = 35.0;
    public int backgroundGenerationResumeDelayTicks = 600;
    public int backgroundGenerationTimeoutSeconds = 20;
    public int backgroundGenerationKeepLoadedTicks = 60;
    public int backgroundGenerationMaxPendingLodSections = 256;
    public int backgroundGenerationHistorySize = 4096;
    public int backgroundGenerationRecheckSeconds = 600;
    public List<String> backgroundGenerationDimensions =
            new ArrayList<>(List.of("minecraft:overworld"));
    public boolean backgroundGenerationIncludeSpectators = false;

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
        positive(errors, "tickInterval", tickInterval);
        if (workerThreads < 1 || workerThreads > MAX_NATIVE_WORKER_THREADS)
            errors.add(
                    "workerThreads must be 1.."
                            + MAX_NATIVE_WORKER_THREADS
                            + " (native pool shutdown bound), got "
                            + workerThreads);
        positive(errors, "dirtyTrackingInterval", dirtyTrackingInterval);
        positive(errors, "debugTrackingInterval", debugTrackingInterval);
        nonnegative(errors, "maxBandwidthBytesPerSec", maxBandwidthBytesPerSec);
        positive(errors, "hashWorkIntervalTicks", hashWorkIntervalTicks);
        positive(errors, "maxHashComputationsPerInterval", maxHashComputationsPerInterval);
        positive(errors, "maxHashMillisPerInterval", maxHashMillisPerInterval);
        positive(errors, "hashRecheckIntervalTicks", hashRecheckIntervalTicks);
        positive(errors, "manifestRefreshIntervalTicks", manifestRefreshIntervalTicks);
        positive(errors, "maintenanceMillisPerSecond", maintenanceMillisPerSecond);
        nonnegative(errors, "maintenanceQuietMillis", maintenanceQuietMillis);
        nonnegative(errors, "maintenanceCooldownMillis", maintenanceCooldownMillis);
        positive(errors, "maxDemandRequestsPerSecond", maxDemandRequestsPerSecond);
        nonnegative(
                errors, "backgroundGenerationMinRadiusChunks", backgroundGenerationMinRadiusChunks);
        if (backgroundGenerationMaxRadiusChunks < 1
                || backgroundGenerationMaxRadiusChunks <= backgroundGenerationMinRadiusChunks)
            errors.add(
                    "backgroundGenerationMaxRadiusChunks must be positive and greater than the"
                        + " minimum radius, got "
                            + backgroundGenerationMaxRadiusChunks);
        if (backgroundGenerationChunksPerMinute < 1
                || backgroundGenerationChunksPerMinute > MAX_BACKGROUND_CHUNKS_PER_MINUTE)
            errors.add(
                    "backgroundGenerationChunksPerMinute must be 1.."
                            + MAX_BACKGROUND_CHUNKS_PER_MINUTE
                            + ", got "
                            + backgroundGenerationChunksPerMinute);
        positive(errors, "backgroundGenerationMaxConcurrent", backgroundGenerationMaxConcurrent);
        positive(
                errors,
                "backgroundGenerationCheckIntervalTicks",
                backgroundGenerationCheckIntervalTicks);
        positive(
                errors,
                "backgroundGenerationCandidatesPerPass",
                backgroundGenerationCandidatesPerPass);
        positive(errors, "backgroundGenerationMaxProbeMillis", backgroundGenerationMaxProbeMillis);
        if (!Double.isFinite(backgroundGenerationStartMspt) || backgroundGenerationStartMspt <= 0)
            errors.add(
                    "backgroundGenerationStartMspt must be finite and positive, got "
                            + backgroundGenerationStartMspt);
        if (!Double.isFinite(backgroundGenerationStopMspt)
                || backgroundGenerationStopMspt <= backgroundGenerationStartMspt)
            errors.add(
                    "backgroundGenerationStopMspt must be finite and greater than start MSPT, got "
                            + backgroundGenerationStopMspt);
        nonnegative(
                errors,
                "backgroundGenerationResumeDelayTicks",
                backgroundGenerationResumeDelayTicks);
        positive(errors, "backgroundGenerationTimeoutSeconds", backgroundGenerationTimeoutSeconds);
        nonnegative(
                errors, "backgroundGenerationKeepLoadedTicks", backgroundGenerationKeepLoadedTicks);
        nonnegative(
                errors,
                "backgroundGenerationMaxPendingLodSections",
                backgroundGenerationMaxPendingLodSections);
        nonnegative(errors, "backgroundGenerationHistorySize", backgroundGenerationHistorySize);
        nonnegative(
                errors, "backgroundGenerationRecheckSeconds", backgroundGenerationRecheckSeconds);
        if (backgroundGenerationDimensions == null || backgroundGenerationDimensions.isEmpty())
            errors.add("backgroundGenerationDimensions must not be empty");
        else {
            var seen = new java.util.HashSet<String>();
            for (String dimension : backgroundGenerationDimensions)
                if (dimension == null
                        || net.minecraft.resources.Identifier.tryParse(dimension) == null
                        || !dimension.contains(":"))
                    errors.add(
                            "backgroundGenerationDimensions contains an invalid namespaced"
                                + " identifier: "
                                    + dimension);
                else if (!seen.add(dimension))
                    errors.add("backgroundGenerationDimensions contains a duplicate: " + dimension);
        }
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
                        && !field.getName().startsWith("backgroundGeneration")
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
