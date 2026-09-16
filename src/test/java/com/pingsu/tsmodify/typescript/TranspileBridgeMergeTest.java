package com.pingsu.tsmodify.typescript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.caoccao.javet.swc4j.wasm.Swc4jWasmRuntime;
import com.caoccao.javet.swc4j.wasm.Swc4jWasmRuntimeStats;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Equivalence evidence for W3: the single crossing bridge call must emit exactly what the two call
 * sequence emits, for every real script fixture and for a synthetic syntax corpus.
 */
class TranspileBridgeMergeTest {
    private static final Path SERVER_SCRIPTS_ROOT = Path.of("test", "server_scripts");
    private static final List<String> SYNTAX_CORPUS = List.of(
            """
            export class Counter {
              private value = 0;
              static instances = 0;
              constructor(readonly label: string = "c") {
                Counter.instances++;
              }
              increment(by: number = 1): number {
                this.value += by;
                return this.value;
              }
              get current(): number {
                return this.value;
              }
            }
            """,
            """
            const pair: [number, string] = [1, "a"];
            const [first, second] = pair;
            const { label, ...rest } = { label: "x", extra: true };
            export const spread = { ...rest, label };
            export const template = `${first}-${second}-${label}`;
            """,
            """
            export enum Mode {
              Add = "add",
              Remove = "remove"
            }
            export const chosen = Mode.Add;
            """,
            """
            export async function load(): Promise<number> {
              const value = await Promise.resolve(1);
              return value ?? 0;
            }
            export function* counter(): Generator<number> {
              yield 1;
            }
            export const chained = load?.name?.length;
            """,
            """
            // A comment that must survive the lowering pass.
            export function* range(limit: number): Generator<number> {
              for (const value of [1, 2, 3]) {
                if (value > limit) {
                  continue;
                }
                yield value;
              }
            }
            """,
            """
            export const arrow = (value: number): number => value * 2;
            export const optional = ({ depth = 0, tags = [] }: any = {}) => (depth ? tags.length : 0);
            """);

    @AfterEach
    void restoreDefaultBridgeCall() {
        System.clearProperty(TypeScriptTranspiler.MERGED_BRIDGE_CALL_PROPERTY);
    }

    @Test
    void packagedBridgeExportsTheMergedEntryPoint() {
        assertTrue(Swc4jWasmRuntime.getInstance().supportsTranspileAndTransform(),
                "the packaged swc4j_bridge.wasm must export swc4j_transpile_and_transform; "
                        + "rebuild it with compileSwc4jWasm");
    }

    @Test
    void mergedCallAndTwoCallSequenceProduceIdenticalBodiesForEveryFixture() throws IOException {
        List<Path> scriptPaths = collectServerScriptPaths();
        assertFalse(scriptPaths.isEmpty(), "expected TypeScript fixtures under " + SERVER_SCRIPTS_ROOT);

        for (Path scriptPath : scriptPaths) {
            String fileName = SERVER_SCRIPTS_ROOT.relativize(scriptPath).toString().replace('\\', '/');
            String source = Files.readString(scriptPath, StandardCharsets.UTF_8);

            assertEquals(
                    transpileModuleBodyTwoCall(fileName, source),
                    transpileModuleBodyMerged(fileName, source),
                    fileName);
        }
    }

    @Test
    void mergedCallAndTwoCallSequenceProduceIdenticalBodiesForSyntaxCorpus() {
        for (int index = 0; index < SYNTAX_CORPUS.size(); index++) {
            String fileName = "corpus/case-" + index + ".ts";
            String source = SYNTAX_CORPUS.get(index);

            assertEquals(
                    transpileModuleBodyTwoCall(fileName, source),
                    transpileModuleBodyMerged(fileName, source),
                    "corpus case " + index);
        }
    }

    @Test
    void mergedCallUsesOneBridgeRoundTripInsteadOfTwo() {
        String source = SYNTAX_CORPUS.get(0);
        // Warm up both paths so that a one time WASM load is not counted as a round trip.
        transpileModuleBodyMerged("warmup/case.ts", source);
        transpileModuleBodyTwoCall("warmup/case.ts", source);

        long beforeMerged = Swc4jWasmRuntimeStats.getInvocationCount();
        transpileModuleBodyMerged("counter/case.ts", source);
        long mergedCost = Swc4jWasmRuntimeStats.getInvocationCount() - beforeMerged;

        long beforeTwoCall = Swc4jWasmRuntimeStats.getInvocationCount();
        transpileModuleBodyTwoCall("counter/case.ts", source);
        long twoCallCost = Swc4jWasmRuntimeStats.getInvocationCount() - beforeTwoCall;

        assertEquals(1L, mergedCost, "the merged pipeline must cross the bridge once");
        assertEquals(2L, twoCallCost, "the two call pipeline must cross the bridge twice");
    }

    @Test
    void reportsWhetherTheBridgeAlreadyLoweredTheSyntax() {
        TypeScriptTranspileConfig config = TypeScriptTranspiler.kubeJsModuleConfig();

        System.setProperty(TypeScriptTranspiler.MERGED_BRIDGE_CALL_PROPERTY, "false");
        assertFalse(TypeScriptTranspiler.transpileSource("case.ts", SYNTAX_CORPUS.get(0), config)
                .syntaxLoweringApplied());

        System.clearProperty(TypeScriptTranspiler.MERGED_BRIDGE_CALL_PROPERTY);
        assertTrue(TypeScriptTranspiler.transpileSource("case.ts", SYNTAX_CORPUS.get(0), config)
                .syntaxLoweringApplied());
    }

    @Test
    void reportsBothCallDurationsForTheFixtureCorpus() throws IOException {
        List<Path> scriptPaths = collectServerScriptPaths();
        List<String> names = new ArrayList<>();
        List<String> sources = new ArrayList<>();
        for (Path scriptPath : scriptPaths) {
            names.add(SERVER_SCRIPTS_ROOT.relativize(scriptPath).toString().replace('\\', '/'));
            sources.add(Files.readString(scriptPath, StandardCharsets.UTF_8));
        }

        // Single warm up pass per variant, then one measured pass each. This is a smoke check that
        // both variants stay runnable; the multi round benchmark lives in
        // com.pingsu.tsmodify.typescript.benchmark.TranspileBridgeBenchmarkMain.
        transpileAll(names, sources, false);
        transpileAll(names, sources, true);

        long start = System.nanoTime();
        transpileAll(names, sources, false);
        long twoCallNanos = System.nanoTime() - start;

        start = System.nanoTime();
        transpileAll(names, sources, true);
        long mergedNanos = System.nanoTime() - start;

        System.out.printf(
                "[W3 measurement] %d fixture(s): two-call %.1f ms/pass, merged %.1f ms/pass, delta %.1f ms/pass%n",
                names.size(),
                twoCallNanos / 1_000_000.0D,
                mergedNanos / 1_000_000.0D,
                (twoCallNanos - mergedNanos) / 1_000_000.0D);
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

    private static String transpileModuleBodyMerged(String fileName, String source) {
        System.clearProperty(TypeScriptTranspiler.MERGED_BRIDGE_CALL_PROPERTY);
        return TypeScriptTranspiler.transpileKubeJsModuleBody(fileName, source).code();
    }

    private static String transpileModuleBodyTwoCall(String fileName, String source) {
        System.setProperty(TypeScriptTranspiler.MERGED_BRIDGE_CALL_PROPERTY, "false");
        try {
            return TypeScriptTranspiler.transpileKubeJsModuleBody(fileName, source).code();
        } finally {
            System.clearProperty(TypeScriptTranspiler.MERGED_BRIDGE_CALL_PROPERTY);
        }
    }

    private static List<Path> collectServerScriptPaths() throws IOException {
        try (Stream<Path> stream = Files.walk(SERVER_SCRIPTS_ROOT)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> TypeScriptTranspiler.isTranspilableTypeScriptFile(path.toString()))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
    }
}
