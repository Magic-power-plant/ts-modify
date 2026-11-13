package com.pingsu.tsmodify.typescript.benchmark;

import com.pingsu.tsmodify.typescript.TranslanguageCompiler;
import com.pingsu.tsmodify.typescript.TypeScriptTranspiler;
import com.pingsu.tsmodify.typescript.cache.TranspileDiskCache;
import com.pingsu.tsmodify.typescript.cache.TranspileMetrics;
import com.pingsu.tsmodify.typescript.module.TypeScriptModuleRegistry;
import com.pingsu.tsmodify.typescript.module.TypeScriptScriptDomain;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Standalone W1 measurement: what a KubeJS script load costs with and without the transpile disk
 * cache, driven through the real {@code TypeScriptModuleRegistry.preload} entry point.
 *
 * <p>This is the in-JVM evidence behind the W1 exit criteria. It is not a substitute for the planned
 * JFR run against a live Minecraft instance, but it exercises the production path: preload, disk
 * cache lookup, compiler transpilation, atomic store, and the KubeJS cache-clear reload boundary.</p>
 *
 * <p>Run through the {@code transpileCacheBenchmark} Gradle task.</p>
 */
public final class TranspileCacheBenchmarkMain {
    private static final Path SERVER_SCRIPTS_ROOT = Path.of("test", "server_scripts");
    private static final TypeScriptScriptDomain DOMAIN =
            new TypeScriptScriptDomain("server", SERVER_SCRIPTS_ROOT);

    private TranspileCacheBenchmarkMain() {
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

        Path cacheRoot = Files.createTempDirectory("tsmodify-transpile-cache-benchmark");
        System.setProperty(TranspileDiskCache.DIRECTORY_PROPERTY, cacheRoot.toString());
        System.out.printf("fixtures=%d cacheRoot=%s%n", names.size(), cacheRoot);

        // Warm the Java compiler and host adapters before measurement.
        compilerPass(names, sources);
        System.out.println("warmup done, Translanguage " + TranslanguageCompiler.version());

        measureCompilerBaseline(names, sources);
        measureCachePass("cold", names, sources);
        measureCachePass("warm", names, sources);
        measureCachePass("warm2", names, sources);

        TranspileDiskCache cache = TranspileDiskCache.shared();
        System.out.printf("cache entries=%d root=%s%n", cache.entryCount(), cache.root());

        deleteRecursively(cacheRoot);
    }

    /** Raw transpilation cost with no registry and no cache involved. */
    private static void measureCompilerBaseline(List<String> names, List<String> sources) {
        long compilerBefore = TranslanguageCompiler.invocationCount();
        long start = System.nanoTime();
        compilerPass(names, sources);
        long elapsed = System.nanoTime() - start;
        System.out.printf("baseline (no cache) %.1f ms, %d compiler call(s)%n",
                elapsed / 1_000_000.0D,
                TranslanguageCompiler.invocationCount() - compilerBefore);
    }

    /** Full production path through {@code preload}. */
    private static void measureCachePass(String label, List<String> names, List<String> sources) {
        // This is the KubeJS reload boundary: the in-memory sources go away, the disk cache survives.
        TypeScriptModuleRegistry.clear();
        TranspileMetrics.reset();
        long compilerBefore = TranslanguageCompiler.invocationCount();
        long start = System.nanoTime();
        for (int index = 0; index < names.size(); index++) {
            TypeScriptModuleRegistry.preload(
                    DOMAIN,
                    "bench",
                    DOMAIN.name(),
                    names.get(index),
                    List.of(sources.get(index).split("\n", -1)));
        }
        long elapsed = System.nanoTime() - start;
        System.out.printf("%-7s %.1f ms, %d compiler call(s)%n",
                label,
                elapsed / 1_000_000.0D,
                TranslanguageCompiler.invocationCount() - compilerBefore);
        System.out.println(TranspileMetrics.summarize(label));
    }

    private static void compilerPass(List<String> names, List<String> sources) {
        for (int index = 0; index < names.size(); index++) {
            TypeScriptTranspiler.transpileKubeJsModuleBody(names.get(index), sources.get(index)).code();
        }
    }

    private static void deleteRecursively(Path root) {
        try (Stream<Path> stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best effort cleanup of a temporary directory.
                }
            });
        } catch (IOException ignored) {
            // Best effort cleanup of a temporary directory.
        }
    }
}
