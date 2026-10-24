package com.dripps.voxyserver.server;

import com.dripps.voxyserver.config.VoxyServerConfig;
import com.dripps.voxyserver.util.RuntimeSwitch;

import java.nio.file.*;
import java.util.*;

/** Actual owned Voxy pool/cleaner and maintenance executor, without a running Minecraft world. */
public final class NativeRuntimeLifecycleTest {
    private static final class Owned implements AutoCloseable {
        final ServerLodEngine engine;
        final LodStreamingService streaming;

        Owned(Path path) {
            engine = new ServerLodEngine(path);
            streaming = new LodStreamingService(engine, new VoxyServerConfig());
            streaming.startWorker();
        }

        @Override
        public void close() {
            try {
                streaming.shutdown();
            } finally {
                engine.shutdown();
            }
        }
    }

    public static void run() throws Exception {
        var baseline = Thread.getAllStackTraces().keySet();
        Path path = Files.createTempDirectory("voxy-runtime-native-");
        var gate = new RuntimeSwitch<Owned>();
        try {
            for (int cycle = 0; cycle < 3; cycle++) {
                gate.start(() -> new Owned(path));
                var old = gate.get();
                require(old.engine.isRunning(), "native engine did not start");
                gate.stop();
                require(
                        !old.engine.isRunning() && gate.get() == null,
                        "native engine still active");
                for (var thread : Thread.getAllStackTraces().keySet())
                    if (!baseline.contains(thread)
                            && (thread.getName().contains("VoxyServer Maintenance")
                                    || thread.getName().equals("Active world cleaner")
                                    || thread.getName().startsWith("Dedicated Voxy Worker #")))
                        require(
                                !thread.isAlive(),
                                "owned thread remains after stop: " + thread.getName());
            }
            Path invalid = path.resolve("not-a-directory");
            Files.writeString(invalid, "fixture");
            try {
                new ServerLodEngine(invalid);
                throw new AssertionError("invalid storage root started");
            } catch (IllegalStateException expected) {
            }
            for (var thread : Thread.getAllStackTraces().keySet())
                if (!baseline.contains(thread)
                        && (thread.getName().equals("Active world cleaner")
                                || thread.getName().startsWith("Dedicated Voxy Worker #")))
                    require(
                            !thread.isAlive(),
                            "failed startup leaked native worker: " + thread.getName());
            require(
                    Files.exists(path.resolve("voxyserver/maintenance-pending-v5.bin")),
                    "stop failed to checkpoint dirty work");
            System.out.println(
                    "PASS NativeRuntimeLifecycleTest: 3 actual native engine/pool/cleaner and"
                        + " maintenance restarts; workers joined and checkpoint preserved (no"
                        + " running world/E2E)");
        } finally {
            gate.stop();
            try (var paths = Files.walk(path)) {
                for (var p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
