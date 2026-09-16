package com.pingsu.tsmodify.typescript.cache;

import com.caoccao.javet.swc4j.options.Swc4jDecoratorsTranspileOption;
import com.caoccao.javet.swc4j.options.Swc4jDecoratorsTranspileOptionEcma;
import com.caoccao.javet.swc4j.options.Swc4jDecoratorsTranspileOptionLegacyTypeScript;
import com.pingsu.tsmodify.typescript.TypeScriptTranspileConfig;
import com.pingsu.tsmodify.typescript.TypeScriptTranspiler;

import java.nio.charset.StandardCharsets;

/**
 * Identity of the whole KubeJS module transpilation pipeline.
 *
 * <p>A cached body may only be reused when the software that produced it is byte-for-byte the same
 * pipeline. The fingerprint therefore covers:</p>
 * <ul>
 *   <li>every field of {@link TypeScriptTranspiler#kubeJsModuleConfig()}, read through the same
 *       accessor the transpiler uses, so the two cannot drift,</li>
 *   <li>the SWC bridge ABI version (the Rust {@code VERSION} constant),</li>
 *   <li>the Java post-processing version (every syntax rewrite that runs after the bridge),</li>
 *   <li>the mod version, when the running jar exposes one, and</li>
 *   <li>the on-disk cache format version.</li>
 * </ul>
 *
 * <p>{@link #SWC4J_BRIDGE_VERSION} mirrors {@code VERSION} in
 * {@code wasm/swc4j-bridge/src/lib.rs} instead of calling
 * {@code Swc4jWasmRuntime#getVersion()}. Initializing {@code Swc4jWasmRuntime} is exactly the
 * ~1.8 s WASM module parse that a warm-cache startup must avoid, and the fingerprint is needed
 * before the first cache lookup. {@code TranspilePipelineFingerprintTest} fails the build when the
 * mirror drifts from the loaded bridge.</p>
 */
public final class TranspilePipelineFingerprint {
    /** Bump on any layout change of {@code <kubejs>/tsmodify-cache/v<N>/}. */
    public static final int CACHE_FORMAT_VERSION = 1;

    /** Mirror of the Rust bridge {@code VERSION} constant; verified by a unit test. */
    public static final String SWC4J_BRIDGE_VERSION = "2.2.0";

    /**
     * Bump whenever the Java post-processing pipeline changes in a way that can alter emitted
     * bodies: {@code typescript/syntax/*} and {@code TypeScriptCommonJsModuleTransformer}.
     */
    public static final String POST_PROCESSING_VERSION = "1";

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
                + "\nparseMode=" + config.parseMode().name()
                + "\nmoduleKind=" + config.moduleKind().name()
                + "\nsourceMap=" + config.sourceMap().name()
                + "\ndecorators=" + decorators(config.decorators())
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
                + "\nbridge=" + SWC4J_BRIDGE_VERSION
                + "\npostProcessing=" + POST_PROCESSING_VERSION
                + "\nmodVersion=" + modVersion();
    }

    private static String compute() {
        String descriptor = describe();
        return TranspileCacheKey.sha256Hex(descriptor.getBytes(StandardCharsets.UTF_8));
    }

    private static String decorators(Swc4jDecoratorsTranspileOption decorators) {
        if (decorators == null) {
            return "none";
        }
        if (decorators instanceof Swc4jDecoratorsTranspileOptionLegacyTypeScript legacyTypeScript) {
            return "legacyTypeScript:emitMetadata=" + legacyTypeScript.isEmitMetadata();
        }
        if (decorators instanceof Swc4jDecoratorsTranspileOptionEcma) {
            return "ecma";
        }
        return decorators.getClass().getSimpleName();
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
