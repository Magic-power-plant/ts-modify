package com.pingsu.rhino.benchmark;

import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.Scriptable;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Runs the exact Rhino evaluation shape used by KubeJS {@code ScriptFile.load()}.
 *
 * <p>This baseline entry deliberately has no compile-time dependency on TS Modify.
 * The optimized entry invokes the production cache directly in its own JVM, so the
 * two classpaths remain part of the benchmark contract without measuring a
 * reflective dispatch that production does not use.</p>
 */
public final class RhinoScriptReloadBenchmarkMain {
    private static final String CACHE_CLASS_NAME =
            "com.pingsu.tsmodify.typescript.rhino.RhinoScriptCompilationCache";
    private static final String SCENARIO_NAME = "rhino_kubejs_script_reload";

    private RhinoScriptReloadBenchmarkMain() {
    }

    public static void main(String[] arguments) throws IOException {
        BenchmarkConfig config = BenchmarkConfig.fromSystemProperties();
        if (config.cacheEnabled()) {
            throw new IllegalStateException("The baseline benchmark must disable the TS Modify cache");
        }
        run(config, EvaluatorSelection.baseline());
    }

    static void runOptimized(ScriptEvaluator evaluator, Class<?> cacheClass) throws IOException {
        BenchmarkConfig config = BenchmarkConfig.fromSystemProperties();
        if (!config.cacheEnabled()) {
            throw new IllegalStateException("The optimized benchmark must enable the TS Modify cache");
        }
        run(config, EvaluatorSelection.optimized(evaluator, cacheClass));
    }

    private static void run(BenchmarkConfig config, EvaluatorSelection evaluatorSelection) throws IOException {
        List<ScriptSource> scripts = createScriptCorpus(config.scriptCount(), config.statementCount());

        long coldLoadNanos = measureColdLoad(scripts, evaluatorSelection.evaluator());
        long[] samples = measureWarmReloads(scripts, evaluatorSelection.evaluator(), config);
        Statistics statistics = Statistics.from(samples);
        writeReport(config, evaluatorSelection, scripts, coldLoadNanos, samples, statistics);
    }

    private static long measureColdLoad(List<ScriptSource> scripts, ScriptEvaluator evaluator) {
        long started = System.nanoTime();
        runScriptLoad(scripts, evaluator);
        return System.nanoTime() - started;
    }

    private static long[] measureWarmReloads(
            List<ScriptSource> scripts,
            ScriptEvaluator evaluator,
            BenchmarkConfig config) {
        for (int round = 0; round < config.warmupRounds(); round++) {
            runScriptLoad(scripts, evaluator);
        }

        long[] samples = new long[config.measurementRounds()];
        long checksum = 0L;
        for (int round = 0; round < samples.length; round++) {
            long started = System.nanoTime();
            checksum ^= runScriptLoad(scripts, evaluator);
            samples[round] = System.nanoTime() - started;
        }
        if (checksum == Long.MIN_VALUE) {
            throw new AssertionError("Script reload checksum reached an impossible sentinel");
        }
        return samples;
    }

    private static long runScriptLoad(List<ScriptSource> scripts, ScriptEvaluator evaluator) {
        Context context = Context.enter();
        Scriptable scope = context.initStandardObjects();
        long checksum = 0L;
        for (ScriptSource script : scripts) {
            String source = String.join("\n", script.lines());
            evaluator.evaluate(context, scope, source, script.sourceName(), 1, null);
            Object value = scope.get(context, script.globalName(), scope);
            if (!(value instanceof Number number) || number.longValue() != script.expectedValue()) {
                throw new AssertionError("Unexpected script result for " + script.sourceName() + ": " + value);
            }
            checksum += number.longValue();
        }
        return checksum;
    }

    private static List<ScriptSource> createScriptCorpus(int scriptCount, int statementCount) {
        List<ScriptSource> scripts = new ArrayList<>(scriptCount);
        for (int scriptIndex = 0; scriptIndex < scriptCount; scriptIndex++) {
            String globalName = "tsmodifyReloadBenchmark" + scriptIndex;
            List<String> lines = new ArrayList<>(4 + statementCount * 2);
            lines.add("var " + globalName + " = (function() {");
            lines.add("var total = " + scriptIndex + ";");
            long expected = scriptIndex;
            for (int statementIndex = 0; statementIndex < statementCount; statementIndex++) {
                int value = (scriptIndex * 31 + statementIndex * 17) % 97;
                lines.add("var value" + statementIndex + " = " + value + ";");
                lines.add("total += value" + statementIndex + ";");
                expected += value;
            }
            lines.add("return total;");
            lines.add("})();");
            scripts.add(new ScriptSource(
                    "kubejs/server_scripts/performance/reload-"
                            + String.format(Locale.ROOT, "%03d", scriptIndex) + ".js",
                    globalName,
                    lines.toArray(String[]::new),
                    expected));
        }
        return scripts;
    }

    private static void writeReport(
            BenchmarkConfig config,
            EvaluatorSelection evaluatorSelection,
            List<ScriptSource> scripts,
            long coldLoadNanos,
            long[] samples,
            Statistics statistics) throws IOException {
        String report = toJson(config, evaluatorSelection, scripts, coldLoadNanos, samples, statistics);
        System.out.printf(Locale.ROOT,
                "Rhino KubeJS script reload: cache=%s cold=%.3f ms median=%.3f ms%n",
                config.cacheEnabled(),
                coldLoadNanos / 1_000_000.0,
                statistics.medianNanos() / 1_000_000.0);

        String reportPath = System.getProperty("tsmodify.rhino.scriptReload.report", "");
        if (reportPath.isBlank()) {
            return;
        }
        Path path = Path.of(reportPath);
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(path, report, StandardCharsets.UTF_8);
        System.out.println("  report=" + path.toAbsolutePath());
    }

    private static String toJson(
            BenchmarkConfig config,
            EvaluatorSelection evaluatorSelection,
            List<ScriptSource> scripts,
            long coldLoadNanos,
            long[] samples,
            Statistics statistics) {
        StringBuilder out = new StringBuilder();
        out.append("{\n");
        appendField(out, "schemaVersion", "3", true, 2);
        appendStringField(out, "generatedAt", Instant.now().toString(), true, 2);
        out.append("  \"metadata\": {\n");
        appendStringField(out, "javaVersion", System.getProperty("java.version"), true, 4);
        appendStringField(out, "javaVendor", System.getProperty("java.vendor"), true, 4);
        appendStringField(out, "osName", System.getProperty("os.name"), true, 4);
        appendStringField(out, "rhinoVersion", rhinoVersion(), true, 4);
        appendField(out, "availableProcessors", Integer.toString(Runtime.getRuntime().availableProcessors()), true, 4);
        appendField(out, "tsModifyPresent", Boolean.toString(evaluatorSelection.tsModifyPresent()), true, 4);
        appendStringField(out, "tsModifyCodeSource", evaluatorSelection.codeSource(), false, 4);
        out.append("  },\n");
        out.append("  \"configuration\": {\n");
        appendStringField(out, "executionPath", evaluatorSelection.executionPath(), true, 4);
        appendField(out, "scriptCacheEnabled", Boolean.toString(config.cacheEnabled()), true, 4);
        appendField(out, "scriptCount", Integer.toString(config.scriptCount()), true, 4);
        appendField(out, "statementCount", Integer.toString(config.statementCount()), true, 4);
        appendField(out, "warmupRounds", Integer.toString(config.warmupRounds()), true, 4);
        appendField(out, "measurementRounds", Integer.toString(config.measurementRounds()), true, 4);
        appendStringField(out, "primingRule", "one cold KubeJS-style load before warm reload timing", false, 4);
        out.append("  },\n");
        out.append("  \"scenario\": {\n");
        appendStringField(out, "name", SCENARIO_NAME, true, 4);
        appendField(out, "operationsPerSample", Integer.toString(scripts.size()), true, 4);
        appendField(out, "coldLoadNanos", Long.toString(coldLoadNanos), true, 4);
        appendField(out, "samplesNanos", Arrays.toString(samples), true, 4);
        appendField(out, "medianNanos", Long.toString(statistics.medianNanos()), true, 4);
        appendField(out, "p95Nanos", Long.toString(statistics.p95Nanos()), true, 4);
        appendField(out, "medianNanosPerScript", decimal(statistics.medianNanos() / (double) scripts.size()), false, 4);
        out.append("  }\n");
        out.append("}\n");
        return out.toString();
    }

    private static String rhinoVersion() {
        return System.getProperty(
                "tsmodify.rhino.scriptReload.rhinoVersion",
                new BenchmarkContext().getImplementationVersion());
    }

    private static void appendField(StringBuilder out, String name, String value, boolean comma, int indent) {
        out.append(" ".repeat(indent)).append('\"').append(name).append("\": ").append(value);
        if (comma) {
            out.append(',');
        }
        out.append('\n');
    }

    private static void appendStringField(StringBuilder out, String name, String value, boolean comma, int indent) {
        out.append(" ".repeat(indent)).append('\"').append(name).append("\": \"")
                .append(escape(value)).append('\"');
        if (comma) {
            out.append(',');
        }
        out.append('\n');
    }

    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static String escape(String value) {
        return value == null ? "unknown" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @FunctionalInterface
    interface ScriptEvaluator {
        Object evaluate(
                Context context,
                Scriptable scope,
                String source,
                String sourceName,
                int lineNumber,
                Object securityDomain);
    }

    private record EvaluatorSelection(
            ScriptEvaluator evaluator,
            boolean tsModifyPresent,
            String codeSource,
            String executionPath) {
        private static EvaluatorSelection baseline() {
            verifyCacheClassIsAbsent();
            return new EvaluatorSelection(
                    Context::evaluateString,
                    false,
                    "absent",
                    "kubejs_script_file_context_evaluate_string");
        }

        private static EvaluatorSelection optimized(ScriptEvaluator evaluator, Class<?> cacheClass) {
            if (!CACHE_CLASS_NAME.equals(cacheClass.getName())) {
                throw new IllegalArgumentException("Unexpected optimized cache class: " + cacheClass.getName());
            }
            return new EvaluatorSelection(
                    evaluator,
                    true,
                    codeSource(cacheClass),
                    "tsmodify_rhino_script_compilation_cache");
        }

        private static void verifyCacheClassIsAbsent() {
            try {
                Class.forName(CACHE_CLASS_NAME);
            } catch (ClassNotFoundException ignored) {
                return;
            }
            throw new IllegalStateException("The baseline benchmark classpath unexpectedly contains TS Modify");
        }

        private static String codeSource(Class<?> type) {
            CodeSource codeSource = type.getProtectionDomain().getCodeSource();
            if (codeSource == null || codeSource.getLocation() == null) {
                return "unknown";
            }
            URI location = URI.create(codeSource.getLocation().toString());
            return Path.of(location).toAbsolutePath().normalize().toString();
        }
    }

    private record ScriptSource(String sourceName, String globalName, String[] lines, long expectedValue) {
    }

    private record BenchmarkConfig(
            int scriptCount,
            int statementCount,
            int warmupRounds,
            int measurementRounds,
            boolean cacheEnabled) {
        private static BenchmarkConfig fromSystemProperties() {
            return new BenchmarkConfig(
                    positiveProperty("tsmodify.rhino.scriptReload.scriptCount", 128),
                    positiveProperty("tsmodify.rhino.scriptReload.statementCount", 512),
                    nonNegativeProperty("tsmodify.rhino.scriptReload.warmupRounds", 5),
                    positiveProperty("tsmodify.rhino.scriptReload.measurementRounds", 9),
                    Boolean.parseBoolean(System.getProperty("tsmodify.rhino.scriptReload.cacheEnabled", "true")));
        }

        private static int positiveProperty(String name, int fallback) {
            int value = Integer.getInteger(name, fallback);
            if (value < 1) {
                throw new IllegalArgumentException(name + " must be at least 1");
            }
            return value;
        }

        private static int nonNegativeProperty(String name, int fallback) {
            int value = Integer.getInteger(name, fallback);
            if (value < 0) {
                throw new IllegalArgumentException(name + " must not be negative");
            }
            return value;
        }
    }

    private static final class BenchmarkContext extends Context {
    }

    private record Statistics(long medianNanos, long p95Nanos) {
        private static Statistics from(long[] samples) {
            long[] sorted = samples.clone();
            Arrays.sort(sorted);
            int medianIndex = sorted.length / 2;
            int p95Index = (int) Math.ceil(sorted.length * 0.95) - 1;
            return new Statistics(sorted[medianIndex], sorted[p95Index]);
        }
    }
}
