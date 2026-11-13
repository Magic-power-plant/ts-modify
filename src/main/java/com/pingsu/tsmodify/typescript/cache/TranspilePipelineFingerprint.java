package com.pingsu.tsmodify.typescript.cache;

import com.pingsu.tsmodify.typescript.TranslanguageCompiler;
import com.pingsu.tsmodify.typescript.TypeScriptTranspileConfig;
import com.pingsu.tsmodify.typescript.TypeScriptTranspiler;

import java.nio.charset.StandardCharsets;

/** Identity of the engine artifact, effective options, host rewrites, and cache format. */
public final class TranspilePipelineFingerprint {
    /** Bump on any layout change of {@code <kubejs>/tsmodify-cache/v<N>/}. */
    public static final int CACHE_FORMAT_VERSION = 1;

    /**
     * Bump whenever the Java post-processing pipeline changes in a way that can alter emitted
     * bodies: {@code typescript/syntax/*} and {@code TypeScriptCommonJsModuleTransformer}.
     */
    public static final String POST_PROCESSING_VERSION = "4";

    private static final String DEV_MOD_VERSION = "dev";
    private static final String VALUE = compute();

    private TranspilePipelineFingerprint() {
    }

    /** Stable 64 character hex identity of the current pipeline. */
    public static String value() {
        return VALUE;
    }

    /** Human readable canonical form, for DEBUG diagnostics and drift tests. */
    public static String describe() {
        return describe(TypeScriptTranspiler.kubeJsModuleConfig());
    }

    static String describe(TypeScriptTranspileConfig config) {
        return "format=" + CACHE_FORMAT_VERSION
                + "\nmode=kubejs-module-body"
                + "\nmoduleKind=" + config.moduleKind().name()
                + "\nsourceMap=" + config.sourceMap().name()
                + "\ndecorators=" + config.decorators().name()
                + "\ntransformTarget=" + (config.transformTarget() == null ? "none" : config.transformTarget().name())
                + "\nkeepComments=" + config.keepComments()
                + "\nstripStaticModuleSyntax=" + config.stripStaticModuleSyntax()
                + "\nrewriteTypeScriptModuleSpecifiers=" + config.rewriteTypeScriptModuleSpecifiers()
                + "\nlowerRuntimeSyntax=" + config.lowerRuntimeSyntax()
                + "\nguardAutomaticSemicolonHazards=" + config.guardAutomaticSemicolonHazards()
                + "\nisolateTopLevelNames=" + config.isolateTopLevelNames()
                + "\nlegacyFallback=" + config.legacyFallback()
                + "\ncopyNonTypeScriptFiles=" + config.copyNonTypeScriptFiles()
                + "\nfailFast=" + config.failFast()
                + "\nengine=Translanguage:" + TranslanguageCompiler.version()
                + "\nengineSha256=" + TranslanguageCompiler.sha256()
                + "\nrhinoPreset=KUBEJS_RHINO_2001_2_3_BUILD_6:lexical=LOOSE:format=READABLE"
                + "\npostProcessing=" + POST_PROCESSING_VERSION
                + "\nmodVersion=" + modVersion();
    }

    private static String compute() {
        String descriptor = describe();
        return TranspileCacheKey.sha256Hex(descriptor.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Implementation version of the running mod jar, or {@link #DEV_MOD_VERSION} when the class is
     * loaded from a directory instead of a versioned jar.
     */
    public static String modVersion() {
        Package currentPackage = TranspilePipelineFingerprint.class.getPackage();
        String version = currentPackage == null ? null : currentPackage.getImplementationVersion();
        return version == null || version.isBlank() ? DEV_MOD_VERSION : version;
    }
}
