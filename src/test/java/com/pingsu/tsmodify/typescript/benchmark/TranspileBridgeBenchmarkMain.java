package com.pingsu.tsmodify.typescript.benchmark;

import com.pingsu.tsmodify.typescript.TypeScriptTranspiler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Standalone W3 measurement: how much of a KubeJS module transpilation pass the second WASM bridge
 * crossing costs.
 *
 * <p>The equivalence of the two variants is asserted by {@code TranspileBridgeMergeTest}; this entry
 * point exists only to produce the timing numbers behind the W3 decision record, because the shared
 * bridge runtime serializes invocations and a JUnit run cannot give a stable measurement.</p>
 *
 * <p>Run through the {@code transpileBridgeBenchmark} Gradle task.</p>
 */
public final class TranspileBridgeBenchmarkMain {
    private static final Path SERVER_SCRIPTS_ROOT = Path.of("test", "server_scripts");
    private static final int WARMUP_ROUNDS = 5;
    private static final int MEASURED_ROUNDS = 21;

    private TranspileBridgeBenchmarkMain() {
    }

    public static void main(String[] args) throws IOException {
        List<String> names = new ArrayList<>();
        List<String> sources = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(SERVER_SCRIPTS_ROOT)) {
            List<Path> scriptPaths = stream.filter(Files::isRegularFile)
                    .filter(path -> TypeScriptTranspiler.isTranspilableTypeScriptFile(path.toString()))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
            for (Path scriptPath : scriptPaths) {
                names.add(SERVER_SCRIPTS_ROOT.relativize(scriptPath).toString().replace('\\', '/'));
                sources.add(Files.readString(scriptPath, StandardCharsets.UTF_8));
            }
        }

        System.out.printf("fixtures=%d warmup=%d measured=%d%n", names.size(), WARMUP_ROUNDS, MEASURED_ROUNDS);
        for (int round = 0; round < WARMUP_ROUNDS; round++) {
            transpileAll(names, sources, false);
            transpileAll(names, sources, true);
        }

        long[] twoCall = new long[MEASURED_ROUNDS];
        long[] merged = new long[MEASURED_ROUNDS];
        for (int round = 0; round < MEASURED_ROUNDS; round++) {
            twoCall[round] = timePass(names, sources, false);
            merged[round] = timePass(names, sources, true);
        }

        long twoCallMedian = median(twoCall);
        long mergedMedian = median(merged);
        System.out.printf("two-call median %.2f ms/pass (min %.2f, max %.2f)%n",
                millis(twoCallMedian), millis(min(twoCall)), millis(max(twoCall)));
        System.out.printf("merged   median %.2f ms/pass (min %.2f, max %.2f)%n",
                millis(mergedMedian), millis(min(merged)), millis(max(merged)));
        System.out.printf("delta    %.2f ms/pass (%.2f%%)%n",
                millis(twoCallMedian - mergedMedian),
                100.0D * (twoCallMedian - mergedMedian) / twoCallMedian);
    }

    private static long timePass(List<String> names, List<String> sources, boolean merged) {
        long start = System.nanoTime();
        transpileAll(names, sources, merged);
        return System.nanoTime() - start;
    }

    private static void transpileAll(List<String> names, List<String> sources, boolean merged) {
        System.setProperty(TypeScriptTranspiler.MERGED_BRIDGE_CALL_PROPERTY, Boolean.toString(merged));
        try {
            for (int index = 0; index < names.size(); index++) {
                TypeScriptTranspiler.transpileKubeJsModuleBody(names.get(index), sources.get(index)).code();
            }
        } finally {
            System.clearProperty(TypeScriptTranspiler.MERGED_BRIDGE_CALL_PROPERTY);
        }
    }

    private static long median(long[] values) {
        long[] sorted = values.clone();
        java.util.Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private static long min(long[] values) {
        long[] sorted = values.clone();
        java.util.Arrays.sort(sorted);
        return sorted[0];
    }

    private static long max(long[] values) {
        long[] sorted = values.clone();
        java.util.Arrays.sort(sorted);
        return sorted[sorted.length - 1];
    }

    private static double millis(long nanos) {
        return nanos / 1_000_000.0D;
    }
}
