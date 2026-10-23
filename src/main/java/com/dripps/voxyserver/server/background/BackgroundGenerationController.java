package com.dripps.voxyserver.server.background;

import com.dripps.voxyserver.config.VoxyServerConfig;

import java.util.*;
import java.util.concurrent.*;

/**
 * Main-thread state machine. Futures are polled only after completion; no world generation runs
 * here.
 */
public final class BackgroundGenerationController<T> {
    public interface Backend<T> {
        T candidate();

        Object key(T target);

        boolean valid(T target);

        CompletableFuture<Boolean> exists(T target);

        CompletableFuture<Boolean> generate(T target);

        boolean ready(T target);

        void ingest(T target);

        void release(T target);
    }

    private enum Phase {
        PROBE,
        READY,
        GENERATING,
        HOLD
    }

    private final class Job {
        final T target;
        final Object key;
        final long began;
        CompletableFuture<Boolean> future;
        Phase phase = Phase.PROBE;
        boolean ticket, cancelled;
        long releaseTick;

        Job(T target, Object key, long began) {
            this.target = target;
            this.key = key;
            this.began = began;
        }
    }

    public record Stats(
            String state,
            double mspt,
            int inFlight,
            int tickets,
            int draining,
            long probes,
            long skippedExisting,
            long started,
            long completed,
            long failed,
            long cancelled,
            long timedOut,
            String lastFailure) {}

    private final Backend<T> backend;
    private final List<Job> jobs = new ArrayList<>();
    private VoxyServerConfig config;
    private long lowSince = -1, lastTick, nextProbeTick, lastStartNanos;
    private boolean hasStarted, closed;
    private String lastFailure = "";
    private String state = "disabled";
    private double mspt;
    private long probes, skippedExisting, started, completed, failed, cancelled, timedOut;

    public BackgroundGenerationController(Backend<T> backend, VoxyServerConfig config) {
        this.backend = backend;
        config.requireValid();
        this.config = config.copy();
    }

    public void updateConfig(VoxyServerConfig next) {
        next.requireValid();
        cancelAll();
        config = next.copy();
        lowSince = -1;
        nextProbeTick = lastTick + config.backgroundGenerationCheckIntervalTicks;
    }

    public void tick(long tick, long now, double observedMspt, boolean suspended) {
        if (closed) return;
        lastTick = tick;
        mspt = observedMspt;
        boolean overloaded =
                !Double.isFinite(mspt) || mspt < 0 || mspt > config.backgroundGenerationStopMspt;
        if (!config.backgroundGenerationEnabled || suspended || overloaded) {
            state =
                    !config.backgroundGenerationEnabled
                            ? "disabled"
                            : overloaded ? "mspt" : "suspended";
            lowSince = -1;
            cancelAll();
            drainCancelled();
            return;
        }
        if (mspt <= config.backgroundGenerationStartMspt) {
            if (lowSince < 0) lowSince = tick;
        } else lowSince = -1;
        boolean headroom =
                lowSince >= 0 && tick - lowSince >= config.backgroundGenerationResumeDelayTicks;
        state = headroom ? "running" : lowSince < 0 ? "headroom" : "cooldown";
        long began = System.nanoTime(),
                limit = config.backgroundGenerationMaxProbeMillis * 1_000_000L;
        for (var it = jobs.iterator(); it.hasNext(); ) {
            Job job = it.next();
            if (job.cancelled) {
                if (job.future == null || job.future.isDone()) it.remove();
                continue;
            }
            if (!backend.valid(job.target)) {
                cancel(job);
                if (job.future == null || job.future.isDone()) it.remove();
                continue;
            }
            if (job.phase == Phase.HOLD) {
                if (tick >= job.releaseTick) {
                    release(job);
                    it.remove();
                }
                continue;
            }
            if (now - job.began
                    >= TimeUnit.SECONDS.toNanos(config.backgroundGenerationTimeoutSeconds)) {
                timedOut++;
                cancel(job);
                if (job.future == null || job.future.isDone()) it.remove();
                continue;
            }
            try {
                if (job.phase == Phase.PROBE && job.future.isDone()) {
                    if (job.future.getNow(false)) {
                        skippedExisting++;
                        it.remove();
                        continue;
                    }
                    job.phase = Phase.READY;
                }
                if (job.phase == Phase.READY
                        && headroom
                        && rateAvailable(now)
                        && System.nanoTime() - began < limit) {
                    // Charge actual generation starts, not merely NBT probes. No catch-up bursts
                    // after idle.
                    lastStartNanos = now;
                    hasStarted = true;
                    started++;
                    job.ticket = true;
                    job.phase = Phase.GENERATING;
                    job.future = Objects.requireNonNull(backend.generate(job.target));
                }
                if (job.phase == Phase.GENERATING && job.future.isDone()) {
                    if (!job.future.getNow(false)) {
                        failed++;
                        release(job);
                        it.remove();
                        continue;
                    }
                    if (backend.ready(job.target)) {
                        backend.ingest(job.target);
                        completed++;
                        job.phase = Phase.HOLD;
                        job.releaseTick = tick + config.backgroundGenerationKeepLoadedTicks;
                        if (config.backgroundGenerationKeepLoadedTicks == 0) {
                            release(job);
                            it.remove();
                        }
                    }
                }
            } catch (RuntimeException error) {
                recordFailure(error);
                cancel(job);
                if (job.future == null || job.future.isDone()) it.remove();
            }
        }
        if (!headroom
                || !rateAvailable(now)
                || tick < nextProbeTick
                || jobs.size() >= config.backgroundGenerationMaxConcurrent) return;
        nextProbeTick = tick + config.backgroundGenerationCheckIntervalTicks;
        for (int attempt = 0;
                attempt < config.backgroundGenerationCandidatesPerPass
                        && jobs.size() < config.backgroundGenerationMaxConcurrent;
                attempt++) {
            if (System.nanoTime() - began >= limit) break;
            T target = backend.candidate();
            if (target == null || !backend.valid(target)) continue;
            Object key = backend.key(target);
            if (jobs.stream().anyMatch(j -> j.key.equals(key))) continue;
            Job job = new Job(target, key, now);
            jobs.add(job);
            try {
                probes++;
                job.future = Objects.requireNonNull(backend.exists(target));
            } catch (RuntimeException error) {
                recordFailure(error);
                jobs.remove(job);
            }
        }
    }

    private boolean rateAvailable(long now) {
        long interval =
                (60_000_000_000L + config.backgroundGenerationChunksPerMinute - 1)
                        / config.backgroundGenerationChunksPerMinute;
        return !hasStarted || now - lastStartNanos >= interval;
    }

    private void recordFailure(RuntimeException error) {
        failed++;
        lastFailure = error.toString();
        if (lastFailure.length() > 256) lastFailure = lastFailure.substring(0, 256);
    }

    private void release(Job job) {
        if (job.ticket) {
            job.ticket = false;
            try {
                backend.release(job.target);
            } catch (RuntimeException error) {
                recordFailure(error);
            }
        }
    }

    private void cancel(Job job) {
        if (job.cancelled) return;
        job.cancelled = true;
        cancelled++;
        release(job);
        // Do not cancel a native/shared future. It remains a concurrency debt until native
        // completion.
    }

    private void cancelAll() {
        for (Job job : jobs) cancel(job);
    }

    private void drainCancelled() {
        jobs.removeIf(job -> job.cancelled && (job.future == null || job.future.isDone()));
    }

    public void shutdown() {
        cancelAll();
        jobs.clear();
        closed = true;
        state = "stopped";
    }

    public Stats stats() {
        int tickets = 0, debt = 0;
        for (Job job : jobs) {
            if (job.ticket) tickets++;
            if (job.cancelled) debt++;
        }
        return new Stats(
                state,
                mspt,
                jobs.size(),
                tickets,
                debt,
                probes,
                skippedExisting,
                started,
                completed,
                failed,
                cancelled,
                timedOut,
                lastFailure);
    }
}
