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
            boundary(path, "maintenanceMillisPerSecond", new int[] {0, 1, 20, 250, 251});
            boundary(
                    path,
                    "maintenanceCooldownJitterMillis",
                    new int[] {-1, 0, 30000, Integer.MAX_VALUE});
            boundary(path, "maintenanceQuietMillis", new int[] {0, 99, 100, 30000, 30001});
            boundary(path, "maintenanceCooldownMillis", new int[] {0, 999, 1000, 180000, 180001});
            boundary(path, "lodStreamRadius", new int[] {-1, 0, 1, 4096, 4097});
            boundary(
                    path,
                    "sourceColumnRecheckSeconds",
                    new int[] {-1, 0, 1, 180, Integer.MAX_VALUE});
            Files.writeString(
                    path,
                    "{\"backgroundGenerationEnabled\":true,\"backgroundGenerationChunksPerMinute\":600000}");
            require(
                    VoxyServerConfig.load(path).validate().isEmpty(),
                    "obsolete config migration failed");
            for (var field : VoxyServerConfig.class.getFields())
                require(
                        !field.getName().startsWith("backgroundGeneration"),
                        "automatic generation control retained");
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
        for (var field : c.getClass().getFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())
                    || field.getType() != int.class) continue;
            String name = field.getName();
            if (java.util.Set.of("maxClientVoxyRadius", "lodStreamRadius", "workerThreads")
                    .contains(name)) continue;
            var wide = new VoxyServerConfig();
            field.setInt(wide, Integer.MAX_VALUE);
            wide.requireValid();
        }
        c.workerThreads = 10000;
        c.requireValid();
        c.workerThreads = 10001;
        require(!c.validate().isEmpty(), "native shutdown bound removed");
        c.workerThreads = 3;
        c = new VoxyServerConfig();
        c.maintenanceQuietMillis = 0;
        c.maintenanceCooldownMillis = 0;
        c.requireValid();
    }

    private static void commandTypes() {
        var cfg = new VoxyServerConfig();
        var modified = cfg.copy();
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
