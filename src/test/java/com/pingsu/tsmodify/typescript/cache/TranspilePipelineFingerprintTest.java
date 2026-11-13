package com.pingsu.tsmodify.typescript.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pingsu.transpiler.DecoratorMode;
import com.pingsu.transpiler.JavaScriptTarget;
import com.pingsu.transpiler.ModuleKind;
import com.pingsu.transpiler.SourceMapMode;
import com.pingsu.tsmodify.typescript.TranslanguageCompiler;
import com.pingsu.tsmodify.typescript.TypeScriptTranspileConfig;
import com.pingsu.tsmodify.typescript.TypeScriptTranspiler;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;

class TranspilePipelineFingerprintTest {
    @Test
    void fingerprintsTheEngineArtifactWithoutCompiling() {
        long before = TranslanguageCompiler.invocationCount();
        String description = TranspilePipelineFingerprint.describe();
        assertTrue(description.contains("engine=Translanguage:2.0.6"), description);
        assertTrue(description.contains("engineSha256=" + TranslanguageCompiler.sha256()), description);
        assertEquals(before, TranslanguageCompiler.invocationCount());
    }

    @Test
    void isStableAndHexEncoded() {
        String first = TranspilePipelineFingerprint.value();

        assertEquals(first, TranspilePipelineFingerprint.value());
        assertEquals(64, first.length());
        assertTrue(first.matches("[0-9a-f]{64}"), first);
    }

    @Test
    void invalidatesBodiesEmittedBeforeTheRhinoCallbackAdapter() {
        String previous = TranspilePipelineFingerprint.describe().replace(
                "\npostProcessing=" + TranspilePipelineFingerprint.POST_PROCESSING_VERSION,
                "\npostProcessing=2");
        assertNotEquals(TranspilePipelineFingerprint.value(),
                TranspileCacheKey.sha256Hex(previous.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void changesForEveryPipelineField() {
        String baseline = fingerprintOf(TypeScriptTranspiler.kubeJsModuleConfig());

        List<UnaryOperator<TypeScriptTranspileConfig.Builder>> variations = List.of(
                builder -> builder.decorators(DecoratorMode.STANDARD),
                builder -> builder.moduleKind(ModuleKind.COMMONJS),
                builder -> builder.sourceMap(SourceMapMode.INLINE),
                builder -> builder.transformTarget(JavaScriptTarget.ES6),
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
