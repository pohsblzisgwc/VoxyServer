package com.dripps.voxyserver;

import com.dripps.voxyserver.config.VoxyServerConfig;
import com.dripps.voxyserver.server.background.*;

import java.util.*;
import java.util.concurrent.*;

public final class BackgroundGenerationTest {
    private static final long SECOND = 1_000_000_000L;

    private static final class Backend implements BackgroundGenerationController.Backend<Integer> {
        final Queue<Integer> candidates = new ArrayDeque<>();
        final Map<Integer, CompletableFuture<Boolean>> probes = new HashMap<>(),
                generations = new HashMap<>();
        final Set<Integer> invalid = new HashSet<>(), owned = new HashSet<>();
        int releases, ingested, samples, maxTickets, retained, overloadAfter = Integer.MAX_VALUE;
        boolean failGeneration;

        @Override
        public void retain(Integer target) {
            require(owned.contains(target), "refresh foreign ticket");
            retained++;
        }

        @Override
        public boolean overloaded() {
            return samples >= overloadAfter;
        }

        @Override
        public Integer candidate() {
            samples++;
            return candidates.poll();
        }

        @Override
        public Object key(Integer target) {
            return target;
        }

        @Override
        public boolean valid(Integer target) {
            return !invalid.contains(target);
        }

        @Override
        public CompletableFuture<Boolean> exists(Integer target) {
            return probes.computeIfAbsent(target, key -> new CompletableFuture<>());
        }

        @Override
        public CompletableFuture<Boolean> generate(Integer target) {
            require(owned.add(target), "duplicate target ticket");
            maxTickets = Math.max(maxTickets, owned.size());
            if (failGeneration) throw new IllegalStateException("ticket setup failure");
            return generations.computeIfAbsent(target, key -> new CompletableFuture<>());
        }

        @Override
        public boolean ready(Integer target) {
            return true;
        }

        @Override
        public void ingest(Integer target) {
            ingested++;
        }

        @Override
        public void release(Integer target) {
            require(owned.remove(target), "foreign or double-released ticket");
            releases++;
        }
    }

    private static VoxyServerConfig config() {
        var c = new VoxyServerConfig();
        c.backgroundGenerationEnabled = true;
        c.backgroundGenerationCheckIntervalTicks = 1;
        c.backgroundGenerationResumeDelayTicks = 0;
        c.backgroundGenerationKeepLoadedTicks = 0;
        c.backgroundGenerationMaxProbeMillis = 5;
        return c;
    }

    public static void runAllTests() throws Exception {
        disabledAndHeadroom();
        completeAndRate();
        cancelAndDrain();
        timeoutsAndMovement();
        failureAndReload();
        sampling();
        duplicates();
        pressure();
        enlargedBatchAndRetention();
        System.out.println(
                "PASS BackgroundGenerationTest: MSPT hysteresis/instant cancellation, global"
                    + " no-burst rate, async debt, movement, timeout, reload, ticket release and"
                    + " bounded annulus sampling");
    }

    private static void disabledAndHeadroom() {
        var b = new Backend();
        b.candidates.add(1);
        var c = config();
        c.backgroundGenerationEnabled = false;
        var s = new BackgroundGenerationController<>(b, c);
        s.tick(0, 0, 1, false);
        require(b.samples == 0 && s.stats().inFlight() == 0, "disabled generator did work");
        c.backgroundGenerationEnabled = true;
        c.backgroundGenerationResumeDelayTicks = 10;
        s.updateConfig(c);
        s.tick(1, SECOND, 20, false);
        s.tick(10, 2 * SECOND, 20, false);
        require(b.samples == 0, "startup quiet period ignored");
        s.tick(11, 3 * SECOND, 26, false);
        s.tick(12, 4 * SECOND, 20, false);
        s.tick(21, 5 * SECOND, 20, false);
        require(b.samples == 0, "headroom oscillation bypassed stable-low period");
        s.tick(22, 6 * SECOND, 20, false);
        require(s.stats().inFlight() == 1, "stable low MSPT did not admit work");
        s.tick(23, 7 * SECOND, 36, false);
        require(
                s.stats().tickets() == 0 && s.stats().cancelled() == 1,
                "high MSPT did not cancel probe immediately");
        b.probes.get(1).complete(false);
        s.tick(24, 8 * SECOND, 20, false);
        require(b.generations.isEmpty(), "late NBT result restarted cancelled generation");
        s.tick(25, 9 * SECOND, Double.NaN, false);
        require(s.stats().state().equals("mspt"), "NaN MSPT admitted work");
    }

    private static void completeAndRate() {
        var b = new Backend();
        b.candidates.addAll(List.of(1, 2));
        var s = new BackgroundGenerationController<>(b, config());
        s.tick(0, 0, 20, false);
        require(
                s.stats().inFlight() == 1 && b.generations.isEmpty(),
                "probe blocked waiting for I/O");
        b.probes.get(1).complete(false);
        s.tick(1, 1, 20, false);
        require(s.stats().started() == 1 && b.owned.size() == 1, "native generation not admitted");
        b.generations.get(1).complete(true);
        s.tick(2, 2, 20, false);
        require(
                b.ingested == 1 && b.releases == 1 && s.stats().inFlight() == 1,
                "completed target not ingested/released");
        s.tick(3, 9 * SECOND, 20, false);
        require(
                b.probes.size() == 2 && s.stats().started() == 1,
                "staging bypassed generation rate");
        s.tick(4, 10 * SECOND + 1, 20, false);
        require(b.probes.size() == 2, "rate window never reopened");
        b.probes.get(2).complete(true);
        s.tick(5, 10 * SECOND + 2, 20, false);
        require(
                s.stats().skippedExisting() == 1 && s.stats().started() == 1,
                "existing full chunk was regenerated");
        s.shutdown();
        require(b.owned.isEmpty(), "shutdown leaked tickets");
    }

    private static void cancelAndDrain() {
        var b = new Backend();
        b.candidates.addAll(List.of(1, 2));
        var s = new BackgroundGenerationController<>(b, config());
        s.tick(0, 0, 20, false);
        b.probes.get(1).complete(false);
        s.tick(1, 1, 20, false);
        s.tick(2, SECOND, 35, false);
        require(b.owned.size() == 1, "equal stop threshold cancelled early");
        s.tick(3, 2 * SECOND, 35.01, false);
        require(
                b.owned.isEmpty() && b.releases == 1 && s.stats().draining() == 1,
                "overload failed immediate ticket release/debt tracking");
        require(!b.generations.get(1).isCancelled(), "shared native future was forcibly cancelled");
        s.tick(4, 12 * SECOND, 20, false);
        require(b.probes.size() == 1, "recovery ignored outstanding cancelled native work");
        b.generations.get(1).complete(true);
        s.tick(5, 13 * SECOND, 20, false);
        require(
                b.ingested == 0 && b.probes.size() == 2 && s.stats().draining() == 0,
                "late native completion ran cancelled callbacks or blocked recovery");
        s.shutdown();
    }

    private static void timeoutsAndMovement() {
        var b = new Backend();
        b.candidates.addAll(List.of(1, 2));
        var c = config();
        c.backgroundGenerationTimeoutSeconds = 1;
        var s = new BackgroundGenerationController<>(b, c);
        s.tick(0, 0, 20, false);
        s.tick(1, SECOND, 20, false);
        require(
                s.stats().timedOut() == 1 && s.stats().draining() == 1,
                "pending probe timeout not bounded");
        b.probes.get(1).complete(false);
        s.tick(2, 2 * SECOND, 20, false);
        require(b.probes.containsKey(2) && b.generations.isEmpty(), "timed-out probe revived");
        b.probes.get(2).complete(false);
        s.tick(3, 2 * SECOND + 1, 20, false);
        b.invalid.add(2);
        s.tick(4, 2 * SECOND + 2, 20, false);
        require(
                b.owned.isEmpty() && s.stats().draining() == 1,
                "teleport/disconnect retained generation ticket");
        b.generations.get(2).complete(true);
        s.tick(5, 3 * SECOND, 20, false);
        require(b.ingested == 0, "moved player received late generation work");
        var holdBackend = new Backend();
        holdBackend.candidates.add(3);
        c.backgroundGenerationKeepLoadedTicks = 5;
        var hold = new BackgroundGenerationController<>(holdBackend, c);
        hold.tick(0, 0, 20, false);
        holdBackend.probes.get(3).complete(false);
        hold.tick(1, 1, 20, false);
        holdBackend.generations.get(3).complete(true);
        hold.tick(2, 2, 20, false);
        require(hold.stats().tickets() == 1, "source capture hold ignored");
        hold.tick(3, 3, 20, true);
        require(hold.stats().tickets() == 0, "backlog/server pause did not release held chunk");
    }

    private static void failureAndReload() {
        var b = new Backend();
        b.candidates.add(1);
        b.failGeneration = true;
        var c = config();
        var s = new BackgroundGenerationController<>(b, c);
        s.tick(0, 0, 20, false);
        b.probes.get(1).complete(false);
        s.tick(1, 1, 20, false);
        require(
                b.releases == 1 && s.stats().failed() == 1 && s.stats().inFlight() == 0,
                "partial ticket setup leaked resources");
        require(!s.stats().lastFailure().isEmpty(), "failed generation has no diagnostic");
        b.failGeneration = false;
        b.candidates.add(2);
        s.tick(2, 11 * SECOND, 20, false);
        b.probes.get(2).complete(false);
        s.tick(3, 11 * SECOND + 1, 20, false);
        c.backgroundGenerationEnabled = false;
        s.updateConfig(c);
        require(b.owned.isEmpty() && s.stats().draining() == 1, "hot disable did not cancel work");
        b.generations.get(2).complete(false);
        s.tick(4, 12 * SECOND, 20, false);
        require(s.stats().inFlight() == 0, "hot-disabled native debt leaked");
        c.backgroundGenerationEnabled = true;
        s.updateConfig(c);
        s.tick(5, 22 * SECOND, 20, false);
        require(!s.stats().state().equals("disabled"), "hot enable requires restart");
    }

    private static void sampling() {
        var random = new Random(11);
        int samples = 0;
        for (int i = 0; i < 100000; i++) {
            var p = BackgroundGenerationArea.sample(-1024, 1024, 16, 64, random);
            if (p != null) {
                require(
                        BackgroundGenerationArea.contains(p, -1024, 1024, 16, 64),
                        "sample outside allowed annulus");
                samples++;
            }
        }
        require(samples > 95000, "annulus sampling wasted most candidates");
        require(
                BackgroundGenerationArea.sample(0, 0, 64, 64, random) == null,
                "empty annulus enumerated forever");
        require(
                !BackgroundGenerationArea.contains(
                        new BackgroundGenerationArea.Point(Integer.MAX_VALUE, 0),
                        Integer.MIN_VALUE,
                        0,
                        0,
                        1024),
                "range arithmetic overflow");
        var b = new Backend();
        var c = config();
        c.backgroundGenerationCandidatesPerPass = 4;
        var s = new BackgroundGenerationController<>(b, c);
        s.tick(0, 0, 20, false);
        require(b.samples == 4, "bounded candidate budget ignored");
    }

    private static void duplicates() {
        var backend = new Backend();
        backend.candidates.addAll(List.of(1, 1, 1, 2));
        var c = config();
        c.backgroundGenerationMaxConcurrent = 4;
        c.backgroundGenerationCandidatesPerPass = 4;
        var scheduler = new BackgroundGenerationController<>(backend, c);
        scheduler.tick(0, 0, 20, false);
        require(
                scheduler.stats().inFlight() == 2
                        && backend.probes.size() == 2
                        && backend.samples == 4,
                "overlapping player candidates duplicated jobs or ignored attempt cap");
        backend.probes.values().forEach(future -> future.complete(false));
        scheduler.tick(1, 1, 20, false);
        require(
                scheduler.stats().started() == 1 && scheduler.stats().tickets() == 1,
                "two ready targets bypassed the global rate limit");
        scheduler.shutdown();
        require(backend.owned.isEmpty(), "duplicate candidate cleanup leaked ticket");
    }

    private static void pressure() {
        var b = new Backend();
        var c = config();
        c.backgroundGenerationMaxConcurrent = 4;
        c.backgroundGenerationChunksPerMinute = 60;
        for (int i = 0; i < 500; i++) b.candidates.add(i);
        var s = new BackgroundGenerationController<>(b, c);
        int maxPending = 0;
        for (int tick = 0; tick < 1200; tick++) {
            long now = tick * 50_000_000L;
            b.probes.values().forEach(f -> f.complete(false));
            b.generations.values().forEach(f -> f.complete(true));
            s.tick(tick, now, tick >= 400 && tick < 600 ? 40 : 20, false);
            maxPending = Math.max(maxPending, s.stats().inFlight());
            require(
                    s.stats().tickets() <= 4 && s.stats().inFlight() <= 4,
                    "multi-job cap exceeded");
            if (tick >= 400 && tick < 600)
                require(b.owned.isEmpty(), "high-MSPT stress interval retained tickets");
        }
        require(
                s.stats().started() <= 60 && s.stats().completed() > 0 && maxPending <= 4,
                "global minute rate or recovery failed");
        s.shutdown();
        require(b.owned.isEmpty(), "pressure cleanup leaked tickets");
        System.out.printf(
                "Background load: 1200 ticks, starts=%d completed=%d cancelled=%d peak pending=%d;"
                        + " no native worldgen/TPS claim%n",
                s.stats().started(), s.stats().completed(), s.stats().cancelled(), maxPending);
    }

    private static void enlargedBatchAndRetention() {
        var c = config();
        c.backgroundGenerationChunksPerMinute = 600000;
        c.backgroundGenerationMaxConcurrent = 500;
        c.backgroundGenerationCandidatesPerPass = 500;
        var b = new Backend();
        for (int i = 0; i < 6000; i++) b.candidates.add(i);
        var s = new BackgroundGenerationController<>(b, c, () -> 0L);
        for (int tick = 0; tick < 11; tick++) {
            b.probes.values().forEach(f -> f.complete(false));
            b.generations.values().forEach(f -> f.complete(true));
            s.tick(tick, tick * 50_000_000L, 20, false);
            require(s.stats().started() <= tick * 500L, "per-tick token batch exceeded");
            require(s.stats().inFlight() <= 500, "expanded concurrency exceeded");
        }
        require(s.stats().started() >= 2500, "scheduler still limited to one start per tick");
        s.shutdown();
        require(b.owned.isEmpty(), "batch shutdown leaked tickets");
        System.out.printf(
                "Expanded generation load: starts=%d, peak tickets=%d; simulated backend only%n",
                s.stats().started(), b.maxTickets);
        var dynamic = new Backend();
        for (int i = 0; i < 500; i++) dynamic.candidates.add(i);
        dynamic.overloadAfter = 3;
        var hot = new BackgroundGenerationController<>(dynamic, c, () -> 0L);
        hot.tick(0, 0, 20, false);
        require(
                dynamic.samples == 3
                        && hot.stats().state().equals("mspt")
                        && hot.stats().draining() == 3,
                "mid-pass overload ignored");
        hot.shutdown();
        var longJob = new Backend();
        longJob.candidates.add(1);
        c.backgroundGenerationTimeoutSeconds = 180;
        c.backgroundGenerationMaxConcurrent = 1;
        var held = new BackgroundGenerationController<>(longJob, c, () -> 0L);
        held.tick(0, 0, 20, false);
        longJob.probes.get(1).complete(false);
        held.tick(1, 1, 20, false);
        held.tick(600, 30 * SECOND, 20, false);
        require(longJob.retained == 0, "ticket refresh too early");
        held.tick(601, 30 * SECOND + 1, 20, false);
        held.tick(1201, 60 * SECOND + 1, 20, false);
        require(
                longJob.retained == 2 && held.stats().tickets() == 1,
                "long-running ticket expired without refresh");
        held.shutdown();
        require(longJob.releases == 1, "refreshed ticket not released once");
        require(
                BackgroundGenerationArea.contains(
                        new BackgroundGenerationArea.Point(2048, 0), 0, 0, 16, 4096),
                "old radius cap remains");
        require(
                !BackgroundGenerationArea.isWorldChunk(
                        new BackgroundGenerationArea.Point(Integer.MAX_VALUE, 0)),
                "world shift overflow admitted");
        for (int i = 0; i < 10000; i++) {
            var p = BackgroundGenerationArea.sample(0, 0, 0, Integer.MAX_VALUE, new Random(i));
            require(
                    p == null || BackgroundGenerationArea.isWorldChunk(p),
                    "extreme radius escaped world");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
