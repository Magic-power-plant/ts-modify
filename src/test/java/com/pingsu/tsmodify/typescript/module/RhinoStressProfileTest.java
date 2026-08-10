package com.pingsu.tsmodify.typescript.module;

import com.pingsu.tsmodify.typescript.TypeScriptTranspiler;
import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.Function;
import dev.latvian.mods.rhino.Scriptable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A repeatable workload for the project's Rhino module boundary. It is excluded
 * from the normal unit suite and executed by the {@code rhinoStressTest} task.
 */
@Tag("performance")
class RhinoStressProfileTest {
    private static final String DOMAIN_NAME = "rhino-stress";
    private static final String LEAF_MODULE_ID = DOMAIN_NAME + ":leaf.ts";
    private static final String DRIVER_MODULE_ID = DOMAIN_NAME + ":driver.ts";
    private static final String WORKING_SET_DRIVER_MODULE_ID = DOMAIN_NAME + ":working-set-driver.ts";
    private static final String LARGE_MODULE_ID = DOMAIN_NAME + ":large-module.ts";
    private static final int SCRIPT_CORPUS_AVAILABLE_FILE_COUNT = 10_000;
    private static final int SCRIPT_CORPUS_FILE_SIZE_BYTES = 10 * 1024;
    private static final Path SCRIPT_CORPUS_ROOT = Path.of("test", "rhino-stress-scripts");
    private static final Path DOMAIN_ROOT = Path.of("rhino-stress", "server_scripts");
    private static final TypeScriptScriptDomain DOMAIN = new TypeScriptScriptDomain(DOMAIN_NAME, DOMAIN_ROOT);

    @AfterEach
    void clearRegistry() {
        TypeScriptModuleRegistry.clear();
    }

    @Test
    void profilesRhinoAndModuleRuntimeBoundaries() throws IOException {
        BenchmarkConfig config = BenchmarkConfig.fromSystemProperties();
        String rhinoVersion = rhinoVersion();
        List<ScriptCorpusModule> scriptCorpus = prepareScriptCorpus(
                collectScriptCorpus(config.scriptCorpusFileCount()));

        List<ScenarioResult> results = List.of(
                measure("cached_require_self", config.iterations(), config,
                        operations -> cachedRequireSelfWork(operations, config)),
                measure("module_resolution", config.iterations(), config,
                        operations -> moduleResolutionWork(operations, config)),
                measure("rhino_cached_module_reference", config.iterations(), config,
                        operations -> cachedModuleReferenceWork(operations, config)),
                measure("rhino_dynamic_require", config.iterations(), config,
                        operations -> dynamicRequireWork(operations, config)),
                measure("rhino_dynamic_require_working_set", config.iterations(), config,
                        operations -> dynamicRequireWorkingSetWork(operations, config)),
                measure("cold_module_graph_load", config.coldModuleCount(), config,
                        operations -> coldModuleGraphWork(operations, config)),
                measure("large_module_factory_load", 1, config,
                        operations -> largeModuleFactoryLoadWork(config)),
                measure("ten_kib_type_script_corpus_module_load", config.scriptCorpusFileCount(),
                        config.scriptCorpusWarmupRounds(), config.scriptCorpusMeasurementRounds(),
                        operations -> generatedScriptCorpusLoadWork(scriptCorpus, config)));

        writeReport(config, rhinoVersion, results);
    }

    private static String rhinoVersion() {
        return new BaselineContext().getImplementationVersion();
    }

    private BenchmarkWork cachedRequireSelfWork(int iterations, BenchmarkConfig config) {
        RuntimeFixture fixture = newFixture(config);
        register(LEAF_MODULE_ID, "leaf.ts", "exports.value = 1;");
        fixture.runtime().requireSelf(LEAF_MODULE_ID, fixture.scope());
        return () -> {
            Object exports = null;
            for (int index = 0; index < iterations; index++) {
                exports = fixture.runtime().requireSelf(LEAF_MODULE_ID, fixture.scope());
            }
            if (!(exports instanceof Scriptable)) {
                throw new AssertionError("Cached module did not return exports");
            }
            return System.identityHashCode(exports);
        };
    }

    private BenchmarkWork moduleResolutionWork(int iterations, BenchmarkConfig config) {
        newFixture(config);
        register(LEAF_MODULE_ID, "leaf.ts", "exports.value = 1;");
        return () -> {
            String moduleId = "";
            for (int index = 0; index < iterations; index++) {
                moduleId = TypeScriptModuleRegistry.resolve(DOMAIN, DRIVER_MODULE_ID, "./leaf");
            }
            if (!LEAF_MODULE_ID.equals(moduleId)) {
                throw new AssertionError("Unexpected module resolution result: " + moduleId);
            }
            return moduleId.length();
        };
    }

    private BenchmarkWork cachedModuleReferenceWork(int iterations, BenchmarkConfig config) {
        RuntimeFixture fixture = driverFixture(config);
        Function run = exportedFunction(fixture, DRIVER_MODULE_ID, "cached");
        return () -> numericResult(run.call(
                fixture.context(), fixture.scope(), fixture.scope(), new Object[]{iterations}), iterations);
    }

    private BenchmarkWork dynamicRequireWork(int iterations, BenchmarkConfig config) {
        RuntimeFixture fixture = driverFixture(config);
        Function run = exportedFunction(fixture, DRIVER_MODULE_ID, "dynamic");
        return () -> numericResult(run.call(
                fixture.context(), fixture.scope(), fixture.scope(), new Object[]{iterations}), iterations);
    }

    private BenchmarkWork dynamicRequireWorkingSetWork(int iterations, BenchmarkConfig config) {
        RuntimeFixture fixture = workingSetDriverFixture(config);
        Function run = exportedFunction(fixture, WORKING_SET_DRIVER_MODULE_ID, "workingSet");
        long expected = workingSetTotal(iterations, config.dynamicModuleCount());
        return () -> numericResult(run.call(
                fixture.context(), fixture.scope(), fixture.scope(), new Object[]{iterations}), expected);
    }

    private BenchmarkWork coldModuleGraphWork(int moduleCount, BenchmarkConfig config) {
        RuntimeFixture fixture = newFixture(config);
        for (int index = 0; index < moduleCount; index++) {
            String moduleId = DOMAIN_NAME + ":module" + index + ".ts";
            String sourceName = "module" + index + ".ts";
            int leftChild = index * 2 + 1;
            int rightChild = leftChild + 1;
            StringBuilder source = new StringBuilder("exports.value = 1;");
            if (leftChild < moduleCount) {
                source.append("exports.value += require(\"./module")
                        .append(leftChild)
                        .append("\").value;");
            }
            if (rightChild < moduleCount) {
                source.append("exports.value += require(\"./module")
                        .append(rightChild)
                        .append("\").value;");
            }
            register(moduleId, sourceName, source.toString());
        }
        String rootModuleId = DOMAIN_NAME + ":module0.ts";
        return () -> {
            Object exports = fixture.runtime().requireSelf(rootModuleId, fixture.scope());
            if (!(exports instanceof Scriptable scriptable)) {
                throw new AssertionError("Cold module graph did not return exports");
            }
            Object value = scriptable.get(fixture.context(), "value", scriptable);
            return numericResult(value, moduleCount);
        };
    }

    private BenchmarkWork largeModuleFactoryLoadWork(BenchmarkConfig config) {
        RuntimeFixture fixture = newFixture(config);
        register(LARGE_MODULE_ID, "large-module.ts", largeModuleBody(config.largeModuleStatementCount()));
        return () -> {
            Object exports = fixture.runtime().requireSelf(LARGE_MODULE_ID, fixture.scope());
            if (!(exports instanceof Scriptable scriptable)) {
                throw new AssertionError("Large module did not return exports");
            }
            Object value = scriptable.get(fixture.context(), "value", scriptable);
            return numericResult(value, config.largeModuleStatementCount() - 1L);
        };
    }

    private static List<ScriptCorpusModule> prepareScriptCorpus(
            List<ScriptCorpusEntry> scriptCorpus) throws IOException {
        List<ScriptCorpusModule> preparedCorpus = new ArrayList<>(scriptCorpus.size());
        for (ScriptCorpusEntry script : scriptCorpus) {
            String source = Files.readString(script.path(), StandardCharsets.UTF_8);
            String body = TypeScriptTranspiler.transpileKubeJsModuleBody(script.sourceName(), source).code();
            preparedCorpus.add(new ScriptCorpusModule(
                    script.moduleId(),
                    script.sourceName(),
                    body,
                    script.exportName(),
                    script.expectedValue()));
        }
        return preparedCorpus;
    }

    private BenchmarkWork generatedScriptCorpusLoadWork(
            List<ScriptCorpusModule> scriptCorpus,
            BenchmarkConfig config) {
        RuntimeFixture fixture = newFixture(config);
        for (ScriptCorpusModule script : scriptCorpus) {
            TypeScriptModuleRegistry.register(DOMAIN, script.moduleId(), script.sourceName(), script.body());
        }
        return () -> {
            long checksum = 0L;
            for (ScriptCorpusModule script : scriptCorpus) {
                Object exports = fixture.runtime().requireSelf(script.moduleId(), fixture.scope());
                if (!(exports instanceof Scriptable scriptable)) {
                    throw new AssertionError("Generated script did not return exports: " + script.sourceName());
                }
                Object value = scriptable.get(fixture.context(), script.exportName(), scriptable);
                checksum += numericResult(value, script.expectedValue());
            }
            return checksum;
        };
    }

    private static List<ScriptCorpusEntry> collectScriptCorpus(int scriptCorpusFileCount) throws IOException {
        List<Path> scriptPaths;
        try (Stream<Path> entries = Files.list(SCRIPT_CORPUS_ROOT)) {
            scriptPaths = entries
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
        if (scriptPaths.size() != SCRIPT_CORPUS_AVAILABLE_FILE_COUNT) {
            throw new AssertionError("Expected " + SCRIPT_CORPUS_AVAILABLE_FILE_COUNT + " generated TypeScript scripts in "
                    + SCRIPT_CORPUS_ROOT + ", found " + scriptPaths.size());
        }

        List<ScriptCorpusEntry> corpus = new ArrayList<>(scriptCorpusFileCount);
        for (int index = 0; index < scriptCorpusFileCount; index++) {
            Path scriptPath = scriptPaths.get(index);
            String identifier = String.format(Locale.ROOT, "%05d", index);
            String expectedFileName = "script-" + identifier + ".ts";
            if (!Files.isRegularFile(scriptPath) || !expectedFileName.equals(scriptPath.getFileName().toString())) {
                throw new AssertionError("Unexpected generated script entry: " + scriptPath);
            }
            if (Files.size(scriptPath) != SCRIPT_CORPUS_FILE_SIZE_BYTES) {
                throw new AssertionError("Generated script must be exactly " + SCRIPT_CORPUS_FILE_SIZE_BYTES
                        + " bytes: " + scriptPath);
            }

            String exportName = "rhinoStressValue" + identifier;
            String prefix = "export const " + exportName
                    + ": number = (function(index: number): number { var payload: string = \"";
            String suffix = "\"; return index + payload.length; })(" + index + ");";
            String source = Files.readString(scriptPath, StandardCharsets.UTF_8);
            int payloadLength = SCRIPT_CORPUS_FILE_SIZE_BYTES - prefix.length() - suffix.length();
            if (source.length() != SCRIPT_CORPUS_FILE_SIZE_BYTES
                    || !source.startsWith(prefix)
                    || !source.endsWith(suffix)) {
                throw new AssertionError("Generated script has an unexpected TypeScript source layout: " + scriptPath);
            }
            String payload = source.substring(prefix.length(), source.length() - suffix.length());
            if (payload.length() != payloadLength || payload.chars().anyMatch(value -> value != 'x')) {
                throw new AssertionError("Generated script has an invalid payload: " + scriptPath);
            }
            corpus.add(new ScriptCorpusEntry(
                    scriptPath,
                    DOMAIN_NAME + ":corpus/" + expectedFileName,
                    "rhino-stress-scripts/" + expectedFileName,
                    exportName,
                    index + payloadLength));
        }
        return corpus;
    }

    private RuntimeFixture driverFixture(BenchmarkConfig config) {
        RuntimeFixture fixture = newFixture(config);
        register(LEAF_MODULE_ID, "leaf.ts", "exports.value = 1;");
        register(DRIVER_MODULE_ID, "driver.ts", """
                var leaf = require("./leaf");
                exports.cached = function(iterations) {
                    var total = 0;
                    for (var index = 0; index < iterations; index++) {
                        total += leaf.value;
                    }
                    return total;
                };
                exports.dynamic = function(iterations) {
                    var total = 0;
                    for (var index = 0; index < iterations; index++) {
                        total += require("./leaf").value;
                    }
                    return total;
                };
                """);
        fixture.runtime().requireSelf(DRIVER_MODULE_ID, fixture.scope());
        return fixture;
    }

    private RuntimeFixture workingSetDriverFixture(BenchmarkConfig config) {
        RuntimeFixture fixture = newFixture(config);
        StringBuilder driverSource = new StringBuilder("var moduleIds = [");
        for (int index = 0; index < config.dynamicModuleCount(); index++) {
            if (index > 0) {
                driverSource.append(", ");
            }
            driverSource.append("\"./working-").append(index).append("\"");
            register(
                    DOMAIN_NAME + ":working-" + index + ".ts",
                    "working-" + index + ".ts",
                    "exports.value = " + (index + 1) + ";");
        }
        driverSource.append("];\n");
        driverSource.append("""
                exports.workingSet = function(iterations) {
                    var total = 0;
                    for (var index = 0; index < iterations; index++) {
                        total += require(moduleIds[index % moduleIds.length]).value;
                    }
                    return total;
                };
                """);
        register(WORKING_SET_DRIVER_MODULE_ID, "working-set-driver.ts", driverSource.toString());
        fixture.runtime().requireSelf(WORKING_SET_DRIVER_MODULE_ID, fixture.scope());
        return fixture;
    }

    private static long workingSetTotal(int iterations, int moduleCount) {
        long completeCycles = iterations / moduleCount;
        long remainder = iterations % moduleCount;
        long cycleTotal = (long) moduleCount * (moduleCount + 1) / 2;
        return completeCycles * cycleTotal + remainder * (remainder + 1) / 2;
    }

    private static String largeModuleBody(int statementCount) {
        StringBuilder source = new StringBuilder();
        for (int index = 0; index < statementCount; index++) {
            source.append("var value").append(index).append(" = ").append(index).append(";\n");
        }
        return source.append("exports.value = value").append(statementCount - 1).append(";\n").toString();
    }

    private Function exportedFunction(RuntimeFixture fixture, String moduleId, String name) {
        Object exports = fixture.runtime().requireSelf(moduleId, fixture.scope());
        if (!(exports instanceof Scriptable scriptable)) {
            throw new AssertionError("Driver module did not return exports");
        }
        Object function = scriptable.get(fixture.context(), name, scriptable);
        if (function instanceof Function result) {
            return result;
        }
        throw new AssertionError("Driver export '" + name + "' is not a function");
    }

    private RuntimeFixture newFixture(BenchmarkConfig config) {
        TypeScriptModuleRegistry.clear();
        Context context = new BaselineContext();
        Scriptable scope = context.initStandardObjects();
        return new RuntimeFixture(
                context,
                scope,
                new TypeScriptModuleRuntime(DOMAIN, context, scope, config.retainResolvedModuleIds()));
    }

    private static void register(String moduleId, String sourceName, String body) {
        TypeScriptModuleRegistry.register(DOMAIN, moduleId, sourceName, body);
    }

    private static long numericResult(Object value, long expected) {
        if (!(value instanceof Number number) || number.longValue() != expected) {
            throw new AssertionError("Unexpected JavaScript result: " + value + ", expected " + expected);
        }
        return number.longValue();
    }

    private static ScenarioResult measure(
            String name,
            int operationsPerSample,
            BenchmarkConfig config,
            BenchmarkWorkFactory workFactory) throws IOException {
        return measure(
                name,
                operationsPerSample,
                config.warmupRounds(),
                config.measurementRounds(),
                workFactory);
    }

    private static ScenarioResult measure(
            String name,
            int operationsPerSample,
            int warmupRounds,
            int measurementRounds,
            BenchmarkWorkFactory workFactory) throws IOException {
        for (int round = 0; round < warmupRounds; round++) {
            workFactory.create(operationsPerSample).run();
        }

        long[] samples = new long[measurementRounds];
        long checksum = 0L;
        for (int round = 0; round < samples.length; round++) {
            BenchmarkWork work = workFactory.create(operationsPerSample);
            long started = System.nanoTime();
            checksum ^= work.run();
            samples[round] = System.nanoTime() - started;
        }
        if (checksum == Long.MIN_VALUE) {
            throw new AssertionError("Benchmark result checksum reached an impossible sentinel");
        }
        return new ScenarioResult(name, operationsPerSample, samples, Statistics.from(samples));
    }

    private static void writeReport(
            BenchmarkConfig config,
            String rhinoVersion,
            List<ScenarioResult> results) throws IOException {
        String generatedAt = Instant.now().toString();
        String report = toJson(config, rhinoVersion, generatedAt, results);
        String optimizationReport = toOptimizationJson(config, rhinoVersion, generatedAt, results);
        System.out.println("Rhino stress profile");
        for (ScenarioResult result : results) {
            Statistics statistics = result.statistics();
            System.out.printf(Locale.ROOT,
                    "  %-32s median=%8.3f ms p95=%8.3f ms throughput=%12.0f ops/s%n",
                    result.name(),
                    nanosToMillis(statistics.medianNanos()),
                    nanosToMillis(statistics.p95Nanos()),
                    result.operationsPerSecond());
        }

        writeReportFile("tsmodify.rhino.stress.report", report, "report");
        writeReportFile("tsmodify.rhino.stress.optimizationReport", optimizationReport, "optimization-report");
    }

    private static void writeReportFile(String propertyName, String report, String label) throws IOException {
        String reportPath = System.getProperty(propertyName, "");
        if (reportPath.isBlank()) {
            return;
        }
        Path path = Path.of(reportPath);
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(path, report, StandardCharsets.UTF_8);
        System.out.println("  " + label + "=" + path.toAbsolutePath());
    }

    private static String toJson(
            BenchmarkConfig config,
            String rhinoVersion,
            String generatedAt,
            List<ScenarioResult> results) {
        StringBuilder out = new StringBuilder();
        out.append("{\n");
        appendJsonField(out, "generatedAt", generatedAt, true, 2);
        out.append("  \"metadata\": {\n");
        appendJsonField(out, "javaVersion", System.getProperty("java.version"), true, 4);
        appendJsonField(out, "javaVendor", System.getProperty("java.vendor"), true, 4);
        appendJsonField(out, "osName", System.getProperty("os.name"), true, 4);
        appendJsonField(out, "rhinoVersion", rhinoVersion, true, 4);
        out.append("    \"availableProcessors\": ").append(Runtime.getRuntime().availableProcessors()).append("\n");
        out.append("  },\n");
        out.append("  \"configuration\": {\n");
        out.append("    \"iterations\": ").append(config.iterations()).append(",\n");
        out.append("    \"warmupRounds\": ").append(config.warmupRounds()).append(",\n");
        out.append("    \"measurementRounds\": ").append(config.measurementRounds()).append(",\n");
        out.append("    \"coldModuleCount\": ").append(config.coldModuleCount()).append(",\n");
        out.append("    \"dynamicModuleCount\": ").append(config.dynamicModuleCount()).append(",\n");
        out.append("    \"largeModuleStatementCount\": ").append(config.largeModuleStatementCount()).append(",\n");
        out.append("    \"scriptCorpusFileCount\": ").append(config.scriptCorpusFileCount()).append(",\n");
        out.append("    \"scriptCorpusWarmupRounds\": ").append(config.scriptCorpusWarmupRounds()).append(",\n");
        out.append("    \"scriptCorpusMeasurementRounds\": ").append(config.scriptCorpusMeasurementRounds()).append(",\n");
        appendJsonField(out, "resolvedModuleIdCache", config.resolvedModuleIdCache(), false, 4);
        out.append("  },\n");
        out.append("  \"scenarios\": [\n");
        for (int index = 0; index < results.size(); index++) {
            ScenarioResult result = results.get(index);
            Statistics statistics = result.statistics();
            out.append("    {\n");
            appendJsonField(out, "name", result.name(), true, 6);
            out.append("      \"operationsPerSample\": ").append(result.operationsPerSample()).append(",\n");
            out.append("      \"samplesNanos\": ").append(Arrays.toString(result.samplesNanos())).append(",\n");
            out.append("      \"minNanos\": ").append(statistics.minNanos()).append(",\n");
            out.append("      \"meanNanos\": ").append(statistics.meanNanos()).append(",\n");
            out.append("      \"medianNanos\": ").append(statistics.medianNanos()).append(",\n");
            out.append("      \"p95Nanos\": ").append(statistics.p95Nanos()).append(",\n");
            out.append("      \"maxNanos\": ").append(statistics.maxNanos()).append(",\n");
            out.append("      \"operationsPerSecond\": ")
                    .append(String.format(Locale.ROOT, "%.3f", result.operationsPerSecond())).append("\n");
            out.append("    }").append(index + 1 == results.size() ? "\n" : ",\n");
        }
        out.append("  ]\n");
        out.append("}\n");
        return out.toString();
    }

    private static String toOptimizationJson(
            BenchmarkConfig config,
            String rhinoVersion,
            String generatedAt,
            List<ScenarioResult> results) {
        StringBuilder out = new StringBuilder();
        out.append("{\n");
        out.append("  \"schemaVersion\": 1,\n");
        appendJsonField(out, "generatedAt", generatedAt, true, 2);
        out.append("  \"scope\": {\n");
        appendJsonField(out, "measures", "Rhino plus the TS Modify module runtime boundary", true, 4);
        appendJsonField(out, "doesNotMeasure", "Minecraft ticks or KubeJS event handlers", false, 4);
        out.append("  },\n");
        out.append("  \"environment\": {\n");
        appendJsonField(out, "javaVersion", System.getProperty("java.version"), true, 4);
        appendJsonField(out, "rhinoVersion", rhinoVersion, true, 4);
        appendJsonField(out, "osName", System.getProperty("os.name"), true, 4);
        out.append("    \"availableProcessors\": ").append(Runtime.getRuntime().availableProcessors()).append("\n");
        out.append("  },\n");
        out.append("  \"configuration\": {\n");
        out.append("    \"iterations\": ").append(config.iterations()).append(",\n");
        out.append("    \"warmupRounds\": ").append(config.warmupRounds()).append(",\n");
        out.append("    \"measurementRounds\": ").append(config.measurementRounds()).append(",\n");
        out.append("    \"coldModuleCount\": ").append(config.coldModuleCount()).append(",\n");
        out.append("    \"dynamicModuleCount\": ").append(config.dynamicModuleCount()).append(",\n");
        out.append("    \"largeModuleStatementCount\": ").append(config.largeModuleStatementCount()).append(",\n");
        out.append("    \"scriptCorpusFileCount\": ").append(config.scriptCorpusFileCount()).append(",\n");
        out.append("    \"scriptCorpusWarmupRounds\": ").append(config.scriptCorpusWarmupRounds()).append(",\n");
        out.append("    \"scriptCorpusMeasurementRounds\": ").append(config.scriptCorpusMeasurementRounds()).append(",\n");
        out.append("    \"jfrRequested\": ")
                .append(Boolean.parseBoolean(System.getProperty("tsmodify.rhino.stress.jfrEnabled", "false")))
                .append(",\n");
        appendJsonField(out, "resolvedModuleIdCache", config.resolvedModuleIdCache(), false, 4);
        out.append("  },\n");
        out.append("  \"optimizationBoundaries\": [\n");
        for (int index = 0; index < results.size(); index++) {
            ScenarioResult result = results.get(index);
            Statistics statistics = result.statistics();
            OptimizationScenarioAdvice advice = optimizationScenarioAdvice(result.name());
            out.append("    {\n");
            appendJsonField(out, "scenario", result.name(), true, 6);
            appendJsonField(out, "phase", advice.phase(), true, 6);
            appendJsonArray(out, "relevantMethods", advice.relevantMethods(), true, 6);
            out.append("      \"operationsPerSample\": ").append(result.operationsPerSample()).append(",\n");
            out.append("      \"medianNanos\": ").append(statistics.medianNanos()).append(",\n");
            out.append("      \"p95Nanos\": ").append(statistics.p95Nanos()).append(",\n");
            out.append("      \"medianNanosPerOperation\": ")
                    .append(decimal(statistics.medianNanos() / (double) result.operationsPerSample())).append(",\n");
            out.append("      \"p95NanosPerOperation\": ")
                    .append(decimal(statistics.p95Nanos() / (double) result.operationsPerSample())).append(",\n");
            out.append("      \"operationsPerSecond\": ").append(decimal(result.operationsPerSecond())).append(",\n");
            appendJsonField(out, "optimizationGuardrail", advice.optimizationGuardrail(), false, 6);
            out.append("    }").append(index + 1 == results.size() ? "\n" : ",\n");
        }
        out.append("  ]\n");
        out.append("}\n");
        return out.toString();
    }

    private static OptimizationScenarioAdvice optimizationScenarioAdvice(String scenario) {
        return switch (scenario) {
            case "cached_require_self" -> new OptimizationScenarioAdvice(
                    "cached module lookup",
                    List.of("TypeScriptModuleRuntime.requireSelf", "TypeScriptModuleRuntime.load"),
                    "Keep cache-hit interception allocation-free and avoid filesystem work.");
            case "module_resolution" -> new OptimizationScenarioAdvice(
                    "module ID resolution",
                    List.of(
                            "TypeScriptModuleRegistry.resolve",
                            "TypeScriptModuleRegistry.resolveExisting",
                            "TypeScriptModuleRegistry.isSourceAvailable"),
                    "Do not add path normalization or file probes after a registry hit.");
            case "rhino_cached_module_reference" -> new OptimizationScenarioAdvice(
                    "Rhino interpreter loop with a captured module value",
                    List.of("dev.latvian.mods.rhino.Interpreter.interpretLoop"),
                    "Treat this as the interpreter baseline before adding an injected callback.");
            case "rhino_dynamic_require" -> new OptimizationScenarioAdvice(
                    "Rhino-to-Java dynamic require boundary",
                    List.of(
                            "TypeScriptModuleRuntime.RequireFunction.call",
                            "TypeScriptModuleRuntime.resolveModuleId",
                            "TypeScriptModuleRuntime.load"),
                    "Preserve successful-request caching; an injected hook must not force re-resolution.");
            case "rhino_dynamic_require_working_set" -> new OptimizationScenarioAdvice(
                    "Rhino-to-Java dynamic require boundary with alternating modules",
                    List.of(
                            "TypeScriptModuleRuntime.RequireFunction.call",
                            "TypeScriptModuleRuntime.resolveModuleId",
                            "TypeScriptModuleRegistry.resolve"),
                    "Keep the single-request fast path and retain successful lookups when requests alternate.");
            case "cold_module_graph_load" -> new OptimizationScenarioAdvice(
                    "cold dependency graph load",
                    List.of(
                            "TypeScriptModuleRuntime.compileFactory",
                            "dev.latvian.mods.rhino.Context.evaluateString"),
                    "Keep factory compilation context-local; never cache a Function across Rhino Context instances.");
            case "large_module_factory_load" -> new OptimizationScenarioAdvice(
                    "large cold module factory compilation and execution",
                    List.of(
                            "TypeScriptModuleRuntime.compileFactory",
                            "dev.latvian.mods.rhino.Context.evaluateString"),
                    "Keep factory compilation context-local; the resolution cache must not retain a Function.");
            case "ten_kib_type_script_corpus_module_load" -> new OptimizationScenarioAdvice(
                    "cold loading of the generated 10,000-file TypeScript corpus",
                    List.of(
                            "TypeScriptModuleRuntime.load",
                            "TypeScriptModuleRuntime.compileFactory",
                            "dev.latvian.mods.rhino.Context.evaluateString"),
                    "Keep module compilation scoped to each Rhino Context and avoid retaining completed factories.");
            default -> throw new IllegalArgumentException("Unknown stress scenario: " + scenario);
        };
    }

    private static void appendJsonArray(
            StringBuilder out,
            String name,
            List<String> values,
            boolean comma,
            int indent) {
        out.append(" ".repeat(indent)).append('"').append(name).append("\": [");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                out.append(", ");
            }
            out.append('"').append(escapeJson(values.get(index))).append('"');
        }
        out.append(']');
        if (comma) {
            out.append(',');
        }
        out.append('\n');
    }

    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static void appendJsonField(StringBuilder out, String name, String value, boolean comma, int indent) {
        out.append(" ".repeat(indent))
                .append('"').append(name).append("\": \"").append(escapeJson(value)).append('"');
        if (comma) {
            out.append(',');
        }
        out.append('\n');
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "unknown";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0;
    }

    private interface BenchmarkWork {
        long run();
    }

    private interface BenchmarkWorkFactory {
        BenchmarkWork create(int operationsPerSample) throws IOException;
    }

    private record RuntimeFixture(Context context, Scriptable scope, TypeScriptModuleRuntime runtime) {
    }

    private record ScriptCorpusEntry(
            Path path,
            String moduleId,
            String sourceName,
            String exportName,
            long expectedValue) {
    }

    private record ScriptCorpusModule(
            String moduleId,
            String sourceName,
            String body,
            String exportName,
            long expectedValue) {
    }

    private record BenchmarkConfig(
            int iterations,
            int warmupRounds,
            int measurementRounds,
            int coldModuleCount,
            int dynamicModuleCount,
            int largeModuleStatementCount,
            int scriptCorpusFileCount,
            int scriptCorpusWarmupRounds,
            int scriptCorpusMeasurementRounds,
            boolean retainResolvedModuleIds) {
        private static BenchmarkConfig fromSystemProperties() {
            return new BenchmarkConfig(
                    positiveProperty("tsmodify.rhino.stress.iterations", 100_000),
                    nonNegativeProperty("tsmodify.rhino.stress.warmupRounds", 3),
                    positiveProperty("tsmodify.rhino.stress.measurementRounds", 7),
                    positiveProperty("tsmodify.rhino.stress.coldModuleCount", 200),
                    positiveProperty("tsmodify.rhino.stress.dynamicModuleCount", 32),
                    positiveProperty("tsmodify.rhino.stress.largeModuleStatementCount", 1_024),
                    positivePropertyAtMost(
                            "tsmodify.rhino.stress.scriptCorpusFileCount",
                            SCRIPT_CORPUS_AVAILABLE_FILE_COUNT,
                            SCRIPT_CORPUS_AVAILABLE_FILE_COUNT),
                    nonNegativeProperty("tsmodify.rhino.stress.scriptCorpusWarmupRounds", 0),
                    positiveProperty("tsmodify.rhino.stress.scriptCorpusMeasurementRounds", 1),
                    Boolean.parseBoolean(System.getProperty(
                            "tsmodify.rhino.stress.retainResolvedModuleIds",
                            "true")));
        }

        private String resolvedModuleIdCache() {
            return retainResolvedModuleIds
                    ? "retain_successful_requests"
                    : "last_request_only";
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

        private static int positivePropertyAtMost(String name, int fallback, int maximum) {
            int value = positiveProperty(name, fallback);
            if (value > maximum) {
                throw new IllegalArgumentException(name + " must not be greater than " + maximum);
            }
            return value;
        }
    }

    private static final class BaselineContext extends Context {
    }

    private record ScenarioResult(String name, int operationsPerSample, long[] samplesNanos, Statistics statistics) {
        private double operationsPerSecond() {
            return operationsPerSample * 1_000_000_000.0 / statistics.medianNanos();
        }
    }

    private record OptimizationScenarioAdvice(
            String phase,
            List<String> relevantMethods,
            String optimizationGuardrail) {
    }

    private record Statistics(long minNanos, long meanNanos, long medianNanos, long p95Nanos, long maxNanos) {
        private static Statistics from(long[] samples) {
            long[] sorted = samples.clone();
            Arrays.sort(sorted);
            long total = 0L;
            for (long sample : samples) {
                total += sample;
            }
            int medianIndex = sorted.length / 2;
            int p95Index = (int) Math.ceil(sorted.length * 0.95) - 1;
            return new Statistics(
                    sorted[0],
                    total / samples.length,
                    sorted[medianIndex],
                    sorted[p95Index],
                    sorted[sorted.length - 1]);
        }
    }
}
