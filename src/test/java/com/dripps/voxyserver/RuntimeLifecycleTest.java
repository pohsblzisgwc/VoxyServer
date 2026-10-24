package com.dripps.voxyserver;

import com.dripps.voxyserver.config.VoxyServerConfig;
import com.dripps.voxyserver.util.RuntimeSwitch;
import com.dripps.voxyserver.util.VoxyUpdateChecker;

import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class RuntimeLifecycleTest {
    private static final class Resource implements AutoCloseable {
        int ticks, closes;
        Runnable closing = () -> {};

        @Override
        public void close() {
            closes++;
            closing.run();
        }
    }

    public static void runAllTests() throws Exception {
        var protocol = new com.dripps.voxyserver.sync.ProtocolActivation();
        protocol.handshakeSent();
        require(
                !protocol.accept(5, 5) && protocol.compatible(),
                "initial reply caused duplicate handshake");
        require(!protocol.accept(5, 5), "duplicate advertisement caused handshake loop");
        require(
                !protocol.accept(0, 5) && !protocol.compatible(),
                "global pause kept protocol active");
        require(protocol.accept(5, 5), "resume did not request handshake");
        protocol.handshakeSent();
        require(
                !protocol.accept(5, 5) && protocol.compatible(),
                "resume reply caused handshake loop");
        require(!protocol.accept(4, 5) && !protocol.compatible(), "old protocol accepted");
        protocol.reset();
        require(!protocol.compatible(), "disconnect retained active state");
        protocol.reset();
        protocol.handshakeSent();
        require(
                !protocol.accept(0, 5) && !protocol.compatible(),
                "disabled initial handshake was not paused");
        require(protocol.accept(5, 5), "client connected while disabled could not resume");
        var oldTracker = new com.dripps.voxyserver.server.PlayerLodTracker();
        var newTracker = new com.dripps.voxyserver.server.PlayerLodTracker();
        var dim = net.minecraft.resources.Identifier.parse("minecraft:overworld");
        long oldEpoch = oldTracker.beginSession(dim, false, 0).streamEpoch();
        long newEpoch = newTracker.beginSession(dim, false, 0).streamEpoch();
        var oldBatch =
                oldTracker.reserveBatch(dim, oldEpoch, new long[] {0}, new long[] {11}, 100, 0);
        var newBatch =
                newTracker.reserveBatch(dim, newEpoch, new long[] {0}, new long[] {22}, 100, 0);
        require(
                oldEpoch != newEpoch
                        && !newTracker.completeDemandBatch(oldEpoch, oldBatch.batchId(), true, 1),
                "pre-disable ACK accepted by new runtime");
        require(
                newTracker.completeDemandBatch(newEpoch, newBatch.batchId(), true, 1),
                "fresh runtime ACK rejected");
        var gate = new RuntimeSwitch<Resource>();
        var created = new AtomicInteger();
        for (int tick = 0; tick < 1000; tick++) {
            var r = gate.get();
            if (r != null) r.ticks++;
        }
        require(created.get() == 0 && gate.get() == null, "disabled runtime performed work");
        for (int cycle = 0; cycle < 100; cycle++) {
            gate.start(
                    () -> {
                        created.incrementAndGet();
                        return new Resource();
                    });
            var active = gate.get();
            gate.start(
                    () -> {
                        throw new AssertionError("duplicate start allocated a service");
                    });
            active.ticks++;
            gate.stop();
            gate.stop();
            require(active.closes == 1 && gate.get() == null, "runtime leaked or double closed");
        }
        require(created.get() == 100, "restart count mismatch");
        var entered = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var old = new Resource();
        old.closing =
                () -> {
                    entered.countDown();
                    try {
                        if (!finish.await(2, TimeUnit.SECONDS))
                            throw new AssertionError("drain timeout");
                    } catch (InterruptedException e) {
                        throw new AssertionError(e);
                    }
                };
        gate.start(() -> old);
        var stopping = new Thread(gate::stop);
        stopping.start();
        require(
                entered.await(2, TimeUnit.SECONDS) && gate.get() == null,
                "callbacks still see runtime while it drains");
        finish.countDown();
        stopping.join(2000);
        require(!stopping.isAlive(), "stop failed to join");
        var bad = new Resource();
        bad.closing =
                () -> {
                    throw new IllegalStateException("test cleanup failure");
                };
        gate.start(() -> bad);
        try {
            gate.stop();
            throw new AssertionError("shutdown failure hidden");
        } catch (IllegalStateException expected) {
        }
        require(gate.get() == null, "failed runtime still admitted events");
        try {
            gate.start(Resource::new);
            throw new AssertionError("failed stop allowed replacement workers");
        } catch (IllegalStateException expected) {
        }
        var fresh = new RuntimeSwitch<Resource>();
        try {
            fresh.start(
                    () -> {
                        throw new IllegalArgumentException("start failure");
                    });
            throw new AssertionError("startup failure hidden");
        } catch (IllegalArgumentException expected) {
        }
        require(fresh.get() == null, "failed startup published runtime");
        fresh.start(Resource::new);
        fresh.stop();
        Path file = Files.createTempFile("voxy-disabled-", ".json");
        try {
            Files.writeString(file, "{\"enabled\":false}");
            var c = VoxyServerConfig.load(file);
            require(!c.enabled && !c.copy().enabled, "startup/copy ignored global disable");
            require(!VoxyServerConfig.reload(file).enabled, "reload ignored disable");
        } finally {
            Files.delete(file);
        }
        var pending = new CountDownLatch(1);
        var update =
                new Thread(
                        () -> {
                            pending.countDown();
                            try {
                                new CountDownLatch(1).await();
                            } catch (InterruptedException expected) {
                            }
                        });
        var field = VoxyUpdateChecker.class.getDeclaredField("updateThread");
        field.setAccessible(true);
        field.set(null, update);
        update.start();
        pending.await();
        VoxyUpdateChecker.stop();
        require(
                !update.isAlive()
                        && field.get(null) == null
                        && VoxyUpdateChecker.getPendingNotice() == null,
                "update worker not drained");
        System.out.println(
                "PASS RuntimeLifecycleTest: 100 disable/re-enable cycles, detach-before-drain,"
                        + " idempotent release, failure isolation, disabled config and update"
                        + " cancellation");
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
