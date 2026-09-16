package com.pingsu.tsmodify.typescript.cache;

import com.caoccao.javet.swc4j.wasm.Swc4jWasmRuntimeStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Startup diagnostics for TypeScript transpilation.
 *
 * <p>Before this existed the KubeJS script load path produced no timing data at all, so every
 * later optimization (disk cache, merged bridge round trip, AOT) was unattributable. The metrics
 * accumulate per script load session and emit one INFO summary plus per-file DEBUG detail.</p>
 *
 * <p>A session ends when it is flushed: explicitly from the KubeJS plugin cache clear hook (which
 * runs on every script reload), or from the JVM shutdown hook so the very first startup session is
 * still reported.</p>
 */
public final class TranspileMetrics {
    /** Result of one transpilation attempt. */
    public enum Outcome {
        /** Served from the disk cache without touching the SWC bridge. */
        HIT,
        /** Transpiled through the SWC bridge because the disk cache had no usable entry. */
        MISS,
        /** Transpiled through the SWC bridge with the disk cache disabled. */
        BYPASS
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("TS Modify");
    private static final int SLOWEST_FILES = 5;
    private static final Object LOCK = new Object();

    private static long files;
    private static long hits;
    private static long misses;
    private static long bypasses;
    private static long sourceBytes;
    private static long transpileNanos;
    private static long sessionStartNanos;
    private static long bridgeCallsAtSessionStart;
    private static long wasmLoadNanosAtSessionStart;
    private static final List<FileTiming> SLOWEST = new ArrayList<>();

    static {
        // A session begins when this class is initialized, so the first file is measured too.
        // Reading Swc4jWasmRuntimeStats must not initialize Swc4jWasmRuntime.
        beginSession();
    }

    private TranspileMetrics() {
    }

    private static void beginSession() {
        sessionStartNanos = System.nanoTime();
        bridgeCallsAtSessionStart = Swc4jWasmRuntimeStats.getInvocationCount();
        wasmLoadNanosAtSessionStart = Swc4jWasmRuntimeStats.getLoadDurationNanos();
    }

    public static void record(String sourceName, int sourceByteCount, long durationNanos, Outcome outcome) {
        ShutdownSummary.register();
        long elapsed = Math.max(0L, durationNanos);
        synchronized (LOCK) {
            files++;
            sourceBytes += Math.max(0, sourceByteCount);
            transpileNanos += elapsed;
            switch (outcome) {
                case HIT -> hits++;
                case MISS -> misses++;
                case BYPASS -> bypasses++;
            }
            offer(new FileTiming(sourceName, elapsed));
        }
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("TS Modify transpile {} {} ({} bytes) in {} [{}]",
                    outcome == Outcome.HIT ? "cache hit" : "cache miss",
                    sourceName,
                    sourceByteCount,
                    formatDuration(elapsed),
                    TranspilePipelineFingerprint.value());
        }
    }

    /**
     * Emits the session summary at INFO, then starts a new session. Does nothing when the session
     * recorded no file.
     */
    public static void flushSummary(String trigger) {
        String summary = summarize(trigger);
        if (!summary.isEmpty()) {
            LOGGER.info(summary);
        }
    }

    /**
     * Renders the session summary and starts a new session.
     *
     * <p>Separated from {@link #flushSummary(String)} so that the benchmark entry points can report
     * the same numbers without depending on a logging backend.</p>
     *
     * @return the summary line, or an empty string when the session recorded no file
     */
    public static String summarize(String trigger) {
        long sessionFiles;
        long sessionHits;
        long sessionMisses;
        long sessionBypasses;
        long sessionBytes;
        long sessionTranspileNanos;
        long sessionWallNanos;
        long bridgeCalls;
        long wasmLoadNanos;
        long wasmLoadAtStart;
        List<FileTiming> slowest;
        synchronized (LOCK) {
            if (files == 0) {
                return "";
            }
            sessionFiles = files;
            sessionHits = hits;
            sessionMisses = misses;
            sessionBypasses = bypasses;
            sessionBytes = sourceBytes;
            sessionTranspileNanos = transpileNanos;
            sessionWallNanos = Math.max(0L, System.nanoTime() - sessionStartNanos);
            bridgeCalls = Math.max(0L, Swc4jWasmRuntimeStats.getInvocationCount() - bridgeCallsAtSessionStart);
            wasmLoadAtStart = wasmLoadNanosAtSessionStart;
            wasmLoadNanos = Swc4jWasmRuntimeStats.getLoadDurationNanos();
            slowest = new ArrayList<>(SLOWEST);
            resetLocked();
        }

        String wasmState = wasmLoadNanos <= 0
                ? "wasm-load not needed"
                : wasmLoadAtStart > 0
                        ? "wasm-load reused (" + formatDuration(wasmLoadNanos) + ")"
                        : "wasm-load " + formatDuration(wasmLoadNanos);
        // The accumulated per-attempt time is the honest cost of transpilation. The session span
        // additionally covers everything between the first transpile and the flush, which in a game
        // run includes unrelated startup work such as world and datapack loading.
        String summary = String.format(
                Locale.ROOT,
                "[TS Modify] transpiled %d TypeScript file(s) in %s (session %s, cache %d/%d hit, %s, "
                        + "%d bridge call(s), %s source, trigger: %s)",
                sessionFiles,
                formatDuration(sessionTranspileNanos),
                formatDuration(sessionWallNanos),
                sessionHits,
                sessionFiles,
                wasmState,
                bridgeCalls,
                formatBytes(sessionBytes),
                trigger);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("[TS Modify] transpile wall time {} across {} cached attempt(s) ({} miss, {} bypass){}",
                    formatDuration(sessionTranspileNanos),
                    sessionFiles,
                    sessionMisses,
                    sessionBypasses,
                    slowestSummary(slowest));
        }
        return summary;
    }

    /** Clears the current session without logging; test seam. */
    public static void reset() {
        synchronized (LOCK) {
            resetLocked();
        }
    }

    private static void resetLocked() {
        files = 0;
        hits = 0;
        misses = 0;
        bypasses = 0;
        sourceBytes = 0;
        transpileNanos = 0;
        SLOWEST.clear();
        beginSession();
    }

    private static void offer(FileTiming timing) {
        SLOWEST.add(timing);
        if (SLOWEST.size() <= SLOWEST_FILES) {
            return;
        }
        SLOWEST.sort(Comparator.comparingLong(FileTiming::durationNanos).reversed());
        SLOWEST.subList(SLOWEST_FILES, SLOWEST.size()).clear();
    }

    private static String slowestSummary(List<FileTiming> slowest) {
        if (slowest.isEmpty()) {
            return "";
        }
        slowest.sort(Comparator.comparingLong(FileTiming::durationNanos).reversed());
        StringBuilder summary = new StringBuilder("; slowest: ");
        for (int index = 0; index < slowest.size() && index < SLOWEST_FILES; index++) {
            if (index > 0) {
                summary.append(", ");
            }
            FileTiming timing = slowest.get(index);
            summary.append(timing.sourceName()).append(' ').append(formatDuration(timing.durationNanos()));
        }
        return summary.toString();
    }

    static String formatDuration(long nanos) {
        if (nanos < 1_000_000L) {
            return String.format(Locale.ROOT, "%.2f ms", nanos / 1_000_000.0D);
        }
        if (nanos < 1_000_000_000L) {
            return String.format(Locale.ROOT, "%d ms", Math.round(nanos / 1_000_000.0D));
        }
        return String.format(Locale.ROOT, "%.2f s", nanos / 1_000_000_000.0D);
    }

    static String formatBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0D);
        }
        return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0D * 1024.0D));
    }

    private record FileTiming(String sourceName, long durationNanos) {
    }

    /**
     * Registers the shutdown summary once, on first use, so the first startup session is reported
     * even though KubeJS only clears caches on a later reload.
     */
    static final class ShutdownSummary {
        private static final AtomicBoolean REGISTERED = new AtomicBoolean();

        private ShutdownSummary() {
        }

        static void register() {
            if (!REGISTERED.compareAndSet(false, true)) {
                return;
            }
            try {
                Runtime.getRuntime().addShutdownHook(new Thread(
                        () -> runQuietly(), "tsmodify-transpile-metrics"));
            } catch (IllegalStateException | SecurityException e) {
                LOGGER.debug("Could not register the TS Modify transpile metrics shutdown hook", e);
            }
        }

        private static void runQuietly() {
            // Forge tears the logging system down before the JVM exits, so a shutdown summary must
            // never be allowed to abort the exit sequence.
            try {
                flushSummary("jvm shutdown");
            } catch (Throwable ignored) {
                // Intentionally ignored: this is the last chance to report, not a correctness path.
            }
        }
    }
}
