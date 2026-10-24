package com.dripps.voxyserver;

import com.dripps.voxyserver.config.VoxyServerConfig;
import com.google.gson.Gson;

import java.nio.file.*;
import java.util.Comparator;

public final class ConfigValidationTest {
    public static void runAllTests() throws Exception {
        Path directory = Files.createTempDirectory("voxy-config-regression-");
        try {
            Path path = directory.resolve("voxyserver.json");
            var first = VoxyServerConfig.load(path);
            require(
                    first.validate().isEmpty() && Files.exists(path),
                    "first startup did not save validated defaults");
            require(VoxyServerConfig.reload(path).validate().isEmpty(), "default reload failed");
            boundary(path, "hashWorkIntervalTicks", new int[] {0, 1, 1200, 1201});
            boundary(path, "maxHashComputationsPerInterval", new int[] {0, 1, 4096, 4097});
            boundary(path, "maxHashMillisPerInterval", new int[] {0, 1, 1000, 1001});
            boundary(
                    path, "hashRecheckIntervalTicks", new int[] {-1, 0, 19, 20, Integer.MAX_VALUE});
            boundary(
                    path,
                    "manifestRefreshIntervalTicks",
                    new int[] {0, 99, 100, Integer.MAX_VALUE});
            boundary(path, "maintenanceMillisPerSecond", new int[] {0, 1, 20, 250, 251});
            boundary(path, "maintenanceQuietMillis", new int[] {0, 99, 100, 30000, 30001});
            boundary(path, "maintenanceCooldownMillis", new int[] {0, 999, 1000, 180000, 180001});
            boundary(path, "maxDemandRequestsPerSecond", new int[] {0, 1, 20, 100, 101});
            boundary(path, "lodStreamRadius", new int[] {-1, 0, 1, 4096, 4097});
            boundary(path, "backgroundGenerationMinRadiusChunks", new int[] {-1, 0, 16, 64, 1024});
            boundary(
                    path, "backgroundGenerationMaxRadiusChunks", new int[] {0, 16, 17, 1024, 1025});
            boundary(
                    path,
                    "backgroundGenerationChunksPerMinute",
                    new int[] {-1, 0, 1, 600, 601, 600000, 600001, Integer.MAX_VALUE});
            boundary(path, "backgroundGenerationMaxConcurrent", new int[] {0, 1, 4, 5});
            boundary(
                    path, "backgroundGenerationCheckIntervalTicks", new int[] {0, 1, 12000, 12001});
            boundary(path, "backgroundGenerationCandidatesPerPass", new int[] {0, 1, 64, 65});
            boundary(path, "backgroundGenerationMaxProbeMillis", new int[] {0, 1, 5, 6});
            boundary(path, "backgroundGenerationResumeDelayTicks", new int[] {-1, 0, 72000, 72001});
            boundary(path, "backgroundGenerationTimeoutSeconds", new int[] {0, 1, 30, 31});
            boundary(path, "backgroundGenerationKeepLoadedTicks", new int[] {-1, 0, 1200, 1201});
            boundary(
                    path,
                    "backgroundGenerationMaxPendingLodSections",
                    new int[] {-1, 0, 65536, 65537});
            boundary(path, "backgroundGenerationHistorySize", new int[] {0, 1, 65536, 65537});
            boundary(path, "backgroundGenerationRecheckSeconds", new int[] {0, 1, 86400, 86401});
            for (String field :
                    new String[] {"backgroundGenerationStartMspt", "backgroundGenerationStopMspt"})
                for (double value :
                        new double[] {
                            Double.NaN, Double.POSITIVE_INFINITY, -1, 0, 25, 35, 200, 201
                        }) {
                    var candidate = new VoxyServerConfig();
                    candidate.getClass().getField(field).setDouble(candidate, value);
                    boolean valid = candidate.validate().isEmpty();
                    String raw = "{\"" + field + "\":" + value + "}";
                    Files.writeString(path, raw);
                    require(
                            VoxyServerConfig.load(path).validate().isEmpty(),
                            "invalid MSPT startup");
                    require(
                            (VoxyServerConfig.reload(path) != null) == valid,
                            "MSPT reload validation differs");
                    if (!valid)
                        try {
                            candidate.requireValid();
                            throw new AssertionError("nonfinite/crossed MSPT accepted");
                        } catch (IllegalArgumentException expected) {
                        }
                    require(Files.readString(path).equals(raw), "invalid MSPT file overwritten");
                }
            for (var dimensions :
                    java.util.Arrays.asList(
                            null,
                            java.util.List.<String>of(),
                            java.util.List.of("bad value"),
                            java.util.List.of("overworld"),
                            java.util.List.of("minecraft:overworld", "minecraft:overworld"))) {
                var candidate = new VoxyServerConfig();
                candidate.backgroundGenerationDimensions = dimensions;
                require(!candidate.validate().isEmpty(), "invalid dimension allowlist accepted");
            }
            commandTypes();
            expandedLimits();
            Files.writeString(path, "null");
            require(
                    VoxyServerConfig.load(path).validate().isEmpty()
                            && Files.readString(path).equals("null"),
                    "null config overwritten");
            require(VoxyServerConfig.reload(path) == null, "reload accepted null");
            Files.writeString(path, "{broken");
            require(
                    VoxyServerConfig.load(path).validate().isEmpty()
                            && Files.readString(path).equals("{broken"),
                    "malformed config overwritten");
            require(VoxyServerConfig.reload(path) == null, "reload accepted malformed JSON");
            System.out.println(
                    "PASS ConfigValidationTest: startup/reload/command/save boundaries and"
                            + " invalid-file preservation");
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
    }

    private static void expandedLimits() throws Exception {
        var c = new VoxyServerConfig();
        require(c.backgroundGenerationChunksPerMinute == 6, "default rate changed");
        for (var field : c.getClass().getFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())
                    || field.getType() != int.class) continue;
            String name = field.getName();
            if (java.util.Set.of(
                            "maxClientVoxyRadius",
                            "lodStreamRadius",
                            "workerThreads",
                            "backgroundGenerationChunksPerMinute",
                            "backgroundGenerationMinRadiusChunks")
                    .contains(name)) continue;
            var wide = new VoxyServerConfig();
            field.setInt(wide, Integer.MAX_VALUE);
            wide.requireValid();
        }
        c.backgroundGenerationChunksPerMinute = 600000;
        c.backgroundGenerationMinRadiusChunks = Integer.MAX_VALUE - 1;
        c.backgroundGenerationMaxRadiusChunks = Integer.MAX_VALUE;
        c.backgroundGenerationStartMspt = 1000;
        c.backgroundGenerationStopMspt = 2000;
        c.workerThreads = 10000;
        c.requireValid();
        c.workerThreads = 10001;
        require(!c.validate().isEmpty(), "native shutdown bound removed");
        c.workerThreads = 3;
        c.backgroundGenerationChunksPerMinute = 600001;
        require(!c.validate().isEmpty(), "600000 cap exceeded");
        c = new VoxyServerConfig();
        c.backgroundGenerationHistorySize = 0;
        c.backgroundGenerationRecheckSeconds = 0;
        c.maintenanceQuietMillis = 0;
        c.maintenanceCooldownMillis = 0;
        c.requireValid();
    }

    private static void commandTypes() {
        var cfg = new VoxyServerConfig();
        var modified = cfg.copy();
        modified.backgroundGenerationEnabled = true;
        require(
                modified.sameStreamingSettings(cfg),
                "background-only settings would restart all stream sessions");
        modified.maxClientVoxyRadius = 512;
        require(!modified.sameStreamingSettings(cfg), "stream configuration change was ignored");
        require(
                com.dripps.voxyserver.config.ConfigValueParser.parse(double.class, "24.5")
                        .equals(24.5),
                "MSPT command parser lost fractions");
        require(
                com.dripps.voxyserver.config.ConfigValueParser.parse(
                                java.util.List.class,
                                "[\"minecraft:overworld\",\"minecraft:the_nether\"]")
                        .equals(java.util.List.of("minecraft:overworld", "minecraft:the_nether")),
                "dimension command parser failed");
        for (String raw : java.util.List.of("no", "1", "null"))
            try {
                com.dripps.voxyserver.config.ConfigValueParser.parse(boolean.class, raw);
                throw new AssertionError("invalid boolean accepted");
            } catch (IllegalArgumentException expected) {
            }
        for (String field :
                java.util.List.of(
                        "backgroundGenerationStartMspt", "backgroundGenerationStopMspt")) {
            for (String raw : java.util.List.of("NaN", "Infinity", "-1", "0"))
                try {
                    var candidate = cfg.copy();
                    try {
                        candidate
                                .getClass()
                                .getField(field)
                                .set(
                                        candidate,
                                        com.dripps.voxyserver.config.ConfigValueParser.parse(
                                                double.class, raw));
                    } catch (ReflectiveOperationException error) {
                        throw new AssertionError(error);
                    }
                    candidate.requireValid();
                    throw new AssertionError("invalid MSPT command accepted");
                } catch (IllegalArgumentException expected) {
                }
        }
    }

    private static void boundary(Path path, String field, int[] values) throws Exception {
        for (int value : values) {
            var candidate = new VoxyServerConfig();
            candidate.getClass().getField(field).setInt(candidate, value);
            boolean valid = candidate.validate().isEmpty();
            String raw = new Gson().toJson(candidate);
            Files.writeString(path, raw);
            var loaded = VoxyServerConfig.load(path);
            var reloaded = VoxyServerConfig.reload(path);
            require(loaded.validate().isEmpty(), "startup applied invalid " + field + "=" + value);
            require(
                    (reloaded != null) == valid,
                    "startup/reload disagree on " + field + "=" + value);
            require(Files.readString(path).equals(raw), "load/reload rewrote input");
            if (!valid) {
                try {
                    new Voxyserver().applyConfigFromCommand(candidate);
                    throw new AssertionError("command accepted invalid config");
                } catch (IllegalArgumentException expected) {
                }
                try {
                    candidate.save(path);
                    throw new AssertionError("save accepted invalid config");
                } catch (IllegalArgumentException expected) {
                }
                require(Files.readString(path).equals(raw), "invalid save rewrote file");
            } else candidate.requireValid();
        }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
