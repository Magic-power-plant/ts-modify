package com.pingsu.tsmodify.typescript.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.caoccao.javet.swc4j.enums.Swc4jEsVersion;
import com.caoccao.javet.swc4j.enums.Swc4jModuleKind;
import com.caoccao.javet.swc4j.enums.Swc4jParseMode;
import com.caoccao.javet.swc4j.enums.Swc4jSourceMapOption;
import com.caoccao.javet.swc4j.wasm.Swc4jWasmRuntime;
import com.caoccao.javet.swc4j.wasm.Swc4jWasmRuntimeStats;
import com.pingsu.tsmodify.typescript.TypeScriptTranspileConfig;
import com.pingsu.tsmodify.typescript.TypeScriptTranspiler;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;

class TranspilePipelineFingerprintTest {
    @Test
    void mirrorsTheVersionOfTheLoadedBridge() {
        // The fingerprint must not initialize Swc4jWasmRuntime (that would cost the ~1.8 s module
        // parse on every warm start), so the Java side mirrors the Rust VERSION constant. This test
        // is what keeps the mirror honest.
        assertEquals(TranspilePipelineFingerprint.SWC4J_BRIDGE_VERSION,
                Swc4jWasmRuntime.getInstance().getVersion());
    }

    @Test
    void doesNotLoadTheWasmRuntimeWhenTheRuntimeIsStillUnloaded() {
        boolean loadedBefore = Swc4jWasmRuntimeStats.getLoadDurationNanos() > 0L;
        long invocationsBefore = Swc4jWasmRuntimeStats.getInvocationCount();

        TranspilePipelineFingerprint.value();
        TranspilePipelineFingerprint.describe();

        assertEquals(invocationsBefore, Swc4jWasmRuntimeStats.getInvocationCount());
        if (!loadedBefore) {
            assertEquals(0L, Swc4jWasmRuntimeStats.getLoadDurationNanos(),
                    "computing the pipeline fingerprint must not load the WASM runtime");
        }
    }

    @Test
    void isStableAndHexEncoded() {
        String first = TranspilePipelineFingerprint.value();

        assertEquals(first, TranspilePipelineFingerprint.value());
        assertEquals(64, first.length());
        assertTrue(first.matches("[0-9a-f]{64}"), first);
    }

    @Test
    void changesForEveryPipelineField() {
        String baseline = fingerprintOf(TypeScriptTranspiler.kubeJsModuleConfig());

        List<UnaryOperator<TypeScriptTranspileConfig.Builder>> variations = List.of(
                builder -> builder.parseMode(Swc4jParseMode.Script),
                builder -> builder.moduleKind(Swc4jModuleKind.Cjs),
                builder -> builder.sourceMap(Swc4jSourceMapOption.Inline),
                builder -> builder.transformTarget(Swc4jEsVersion.ES2015),
                builder -> builder.transformTarget(null),
                builder -> builder.keepComments(false),
                builder -> builder.stripStaticModuleSyntax(true),
                builder -> builder.rewriteTypeScriptModuleSpecifiers(true),
                builder -> builder.lowerRuntimeSyntax(false),
                builder -> builder.guardAutomaticSemicolonHazards(false),
                builder -> builder.isolateTopLevelNames(true),
                builder -> builder.legacyFallback(true),
                builder -> builder.copyNonTypeScriptFiles(true),
                builder -> builder.failFast(false));

        for (int index = 0; index < variations.size(); index++) {
            TypeScriptTranspileConfig.Builder builder =
                    variations.get(index).apply(TypeScriptTranspiler.kubeJsModuleConfig().toBuilder());
            assertNotEquals(baseline, fingerprintOf(builder.build()),
                    "pipeline field variation " + index + " must change the fingerprint");
        }
    }

    private static String fingerprintOf(TypeScriptTranspileConfig config) {
        byte[] descriptor = TranspilePipelineFingerprint.describe(config).getBytes(StandardCharsets.UTF_8);
        return TranspileCacheKey.sha256Hex(descriptor);
    }
}
