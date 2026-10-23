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
        if (maxClientVoxyRadius < 1 || maxClientVoxyRadius > 4096) {
            errors.add(
                    "maxClientVoxyRadius must be between 1 and 4096, got " + maxClientVoxyRadius);
        }
        if (lodStreamRadius < 1 || lodStreamRadius > 4096)
            errors.add("lodStreamRadius must be 1..4096, got " + lodStreamRadius);
        if (maxSectionsPerTickPerPlayer < 1)
            errors.add(
                    "maxsectionspertickperplayer must be at least 1, got "
                            + maxSectionsPerTickPerPlayer);
        if (sectionsPerPacket < 1)
            errors.add("sectionsperpacket must be at least 1, got " + sectionsPerPacket);
        if (tickInterval < 1) errors.add("tickinterval must be at least 1, got " + tickInterval);
        if (workerThreads < 1 || workerThreads > 32)
            errors.add("workerthreads must be between 1 and 32, got " + workerThreads);
        if (dirtyTrackingInterval < 1)
            errors.add("dirtytrackinginterval must be at least 1, got " + dirtyTrackingInterval);
        if (debugTrackingInterval < 1)
            errors.add("debugtrackinginterval must be at least 1, got " + debugTrackingInterval);
        if (maxBandwidthBytesPerSec < 0)
            errors.add(
                    "maxbandwidthbytespersec cannot be negative, got " + maxBandwidthBytesPerSec);
        if (hashWorkIntervalTicks < 1 || hashWorkIntervalTicks > 1200)
            errors.add("hashWorkIntervalTicks must be 1..1200, got " + hashWorkIntervalTicks);
        if (maxHashComputationsPerInterval < 1 || maxHashComputationsPerInterval > 4096)
            errors.add(
                    "maxHashComputationsPerInterval must be 1..4096, got "
                            + maxHashComputationsPerInterval);
        if (maxHashMillisPerInterval < 1 || maxHashMillisPerInterval > 1000)
            errors.add("maxHashMillisPerInterval must be 1..1000, got " + maxHashMillisPerInterval);
        if (hashRecheckIntervalTicks < hashWorkIntervalTicks)
            errors.add(
                    "hashRecheckIntervalTicks must be >= hashWorkIntervalTicks, got "
                            + hashRecheckIntervalTicks);
        if (manifestRefreshIntervalTicks < 100)
            errors.add(
                    "manifestRefreshIntervalTicks must be >= 100, got "
                            + manifestRefreshIntervalTicks);
        if (maintenanceMillisPerSecond < 1 || maintenanceMillisPerSecond > 250)
            errors.add("maintenanceMillisPerSecond must be 1..250");
        if (maintenanceQuietMillis < 100 || maintenanceQuietMillis > 30_000)
            errors.add("maintenanceQuietMillis must be 100..30000");
        if (maintenanceCooldownMillis < 1000 || maintenanceCooldownMillis > 180_000)
            errors.add("maintenanceCooldownMillis must be 1000..180000");
        if (maxDemandRequestsPerSecond < 1 || maxDemandRequestsPerSecond > 100)
            errors.add("maxDemandRequestsPerSecond must be 1..100");
        if (backgroundGenerationMinRadiusChunks < 0 || backgroundGenerationMinRadiusChunks > 1023)
            errors.add(
                    "backgroundGenerationMinRadiusChunks must be 0..1023, got "
                            + backgroundGenerationMinRadiusChunks);
        if (backgroundGenerationMaxRadiusChunks < 1
                || backgroundGenerationMaxRadiusChunks > 1024
                || backgroundGenerationMaxRadiusChunks <= backgroundGenerationMinRadiusChunks)
            errors.add(
                    "backgroundGenerationMaxRadiusChunks must be 1..1024 and greater than the"
                        + " minimum radius, got "
                            + backgroundGenerationMaxRadiusChunks);
        if (backgroundGenerationChunksPerMinute < 1 || backgroundGenerationChunksPerMinute > 600)
            errors.add(
                    "backgroundGenerationChunksPerMinute must be 1..600, got "
                            + backgroundGenerationChunksPerMinute);
        if (backgroundGenerationMaxConcurrent < 1 || backgroundGenerationMaxConcurrent > 4)
            errors.add(
                    "backgroundGenerationMaxConcurrent must be 1..4, got "
                            + backgroundGenerationMaxConcurrent);
        if (backgroundGenerationCheckIntervalTicks < 1
                || backgroundGenerationCheckIntervalTicks > 12000)
            errors.add(
                    "backgroundGenerationCheckIntervalTicks must be 1..12000, got "
                            + backgroundGenerationCheckIntervalTicks);
        if (backgroundGenerationCandidatesPerPass < 1 || backgroundGenerationCandidatesPerPass > 64)
            errors.add(
                    "backgroundGenerationCandidatesPerPass must be 1..64, got "
                            + backgroundGenerationCandidatesPerPass);
        if (backgroundGenerationMaxProbeMillis < 1 || backgroundGenerationMaxProbeMillis > 5)
            errors.add(
                    "backgroundGenerationMaxProbeMillis must be 1..5, got "
                            + backgroundGenerationMaxProbeMillis);
        if (!Double.isFinite(backgroundGenerationStartMspt)
                || backgroundGenerationStartMspt <= 0
                || backgroundGenerationStartMspt > 100)
            errors.add(
                    "backgroundGenerationStartMspt must be finite and >0..100, got "
                            + backgroundGenerationStartMspt);
        if (!Double.isFinite(backgroundGenerationStopMspt)
                || backgroundGenerationStopMspt <= backgroundGenerationStartMspt
                || backgroundGenerationStopMspt > 200)
            errors.add(
                    "backgroundGenerationStopMspt must be finite, greater than start MSPT and"
                        + " <=200, got "
                            + backgroundGenerationStopMspt);
        if (backgroundGenerationResumeDelayTicks < 0
                || backgroundGenerationResumeDelayTicks > 72000)
            errors.add(
                    "backgroundGenerationResumeDelayTicks must be 0..72000, got "
                            + backgroundGenerationResumeDelayTicks);
        if (backgroundGenerationTimeoutSeconds < 1 || backgroundGenerationTimeoutSeconds > 30)
            errors.add(
                    "backgroundGenerationTimeoutSeconds must be 1..30, got "
                            + backgroundGenerationTimeoutSeconds);
        if (backgroundGenerationKeepLoadedTicks < 0 || backgroundGenerationKeepLoadedTicks > 1200)
            errors.add(
                    "backgroundGenerationKeepLoadedTicks must be 0..1200, got "
                            + backgroundGenerationKeepLoadedTicks);
        if (backgroundGenerationMaxPendingLodSections < 0
                || backgroundGenerationMaxPendingLodSections > 65536)
            errors.add(
                    "backgroundGenerationMaxPendingLodSections must be 0..65536, got "
                            + backgroundGenerationMaxPendingLodSections);
        if (backgroundGenerationHistorySize < 1 || backgroundGenerationHistorySize > 65536)
            errors.add(
                    "backgroundGenerationHistorySize must be 1..65536, got "
                            + backgroundGenerationHistorySize);
        if (backgroundGenerationRecheckSeconds < 1 || backgroundGenerationRecheckSeconds > 86400)
            errors.add(
                    "backgroundGenerationRecheckSeconds must be 1..86400, got "
                            + backgroundGenerationRecheckSeconds);
        if (backgroundGenerationDimensions == null
                || backgroundGenerationDimensions.isEmpty()
                || backgroundGenerationDimensions.size() > 64)
            errors.add("backgroundGenerationDimensions must contain 1..64 dimension identifiers");
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
