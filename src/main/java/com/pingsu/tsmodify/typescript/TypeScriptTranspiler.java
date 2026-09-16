package com.pingsu.tsmodify.typescript;

import com.caoccao.javet.swc4j.Swc4j;
import com.caoccao.javet.swc4j.enums.Swc4jMediaType;
import com.caoccao.javet.swc4j.enums.Swc4jParseMode;
import com.caoccao.javet.swc4j.enums.Swc4jSourceMapOption;
import com.caoccao.javet.swc4j.exceptions.Swc4jCoreException;
import com.caoccao.javet.swc4j.options.Swc4jTransformOptions;
import com.caoccao.javet.swc4j.options.Swc4jTranspileOptions;
import com.caoccao.javet.swc4j.outputs.Swc4jTransformOutput;
import com.caoccao.javet.swc4j.outputs.Swc4jTranspileOutput;
import com.caoccao.javet.swc4j.wasm.Swc4jWasmRuntime;
import com.pingsu.tsmodify.typescript.syntax.TypeScriptDeclarationStripper;
import com.pingsu.tsmodify.typescript.syntax.TypeScriptAutomaticSemicolonGuard;
import com.pingsu.tsmodify.typescript.syntax.TypeScriptCommonJsModuleTransformer;
import com.pingsu.tsmodify.typescript.syntax.TypeScriptEnumTransformer;
import com.pingsu.tsmodify.typescript.syntax.TypeScriptFileScopeIsolator;
import com.pingsu.tsmodify.typescript.syntax.TypeScriptSyntaxStripper;

import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TypeScriptTranspiler {
    private static final Pattern MODULE_SPECIFIER_PATTERN = Pattern.compile(
            "(\\bfrom\\s*|\\bimport\\s*\\(\\s*|\\brequire\\s*\\(\\s*|\\bimport\\s+)(['\"])([^'\"]+)(['\"])");
    /**
     * Rollback switch for the merged bridge call. Set to {@code false} to force the two call
     * sequence, which is what the equivalence test compares against.
     */
    public static final String MERGED_BRIDGE_CALL_PROPERTY = "tsmodify.mergedTranspileCall";
    private static final Swc4j SWC4J = new Swc4j();

    private TypeScriptTranspiler() {
    }

    public static List<String> transpile(String fileName, List<String> sourceLines) {
        if (!isTranspilableTypeScriptFile(fileName)) {
            return sourceLines;
        }

        String source = String.join("\n", sourceLines);
        TypeScriptSourceTranspileResult result = transpileSource(fileName, source, TypeScriptTranspileConfig.KUBEJS);
        return Arrays.asList(normalizeLineEndings(result.code()).split("\n", -1));
    }

    public static TypeScriptSourceTranspileResult transpileSource(
            String fileName,
            String source,
            TypeScriptTranspileConfig config) {
        if (!isTranspilableTypeScriptFile(fileName)) {
            return new TypeScriptSourceTranspileResult(source, null, false);
        }

        String swcSource;
        try {
            swcSource = config.guardAutomaticSemicolonHazards()
                    ? TypeScriptAutomaticSemicolonGuard.guard(source)
                    : source;
        } catch (Throwable preProcessFailure) {
            throw TypeScriptErrorReporter.transpileFailure(
                    fileName, source, "TypeScript pre-processing", preProcessFailure);
        }
        TypeScriptSourceTranspileResult result;
        try {
            result = canMergeBridgeCalls(config)
                    ? mergedBridgeTranspile(fileName, swcSource, config)
                    : swcTranspile(fileName, swcSource, config);
        } catch (Throwable swcFailure) {
            if (config.legacyFallback()) {
                try {
                    return new TypeScriptSourceTranspileResult(legacyTranspile(fileName, source, config), null, true);
                } catch (Throwable fallbackFailure) {
                    throw TypeScriptErrorReporter.transpileFailure(
                            fileName, source, "legacy fallback", fallbackFailure);
                }
            }
            throw TypeScriptErrorReporter.transpileFailure(fileName, source, "SWC transpilation", swcFailure);
        }

        try {
            String code = result.code();
            if (config.rewriteTypeScriptModuleSpecifiers()) {
                code = rewriteTypeScriptModuleSpecifiers(code);
            }
            if (config.stripStaticModuleSyntax()) {
                code = TypeScriptDeclarationStripper.stripImportStatements(code);
            }
            if (config.isolateTopLevelNames()) {
                code = TypeScriptFileScopeIsolator.isolate(fileName, code);
            }
            if (config.stripStaticModuleSyntax()) {
                code = TypeScriptDeclarationStripper.stripExportStatements(code);
            }
            if (!result.syntaxLoweringApplied() && config.transformTarget() != null) {
                code = transformJavaScript(fileName, code, config);
            }
            if (config.lowerRuntimeSyntax()) {
                code = TypeScriptSyntaxStripper.stripPostEs2015Syntax(code);
            }
            return new TypeScriptSourceTranspileResult(
                    code, result.sourceMap(), false, result.syntaxLoweringApplied());
        } catch (Throwable postProcessFailure) {
            throw TypeScriptErrorReporter.transpileFailure(
                    fileName, source, "TypeScript post-processing", postProcessFailure);
        }
    }

    /**
     * Whether the TypeScript strip and the syntax lowering can share one bridge invocation.
     *
     * <p>Only when nothing has to run in Java between the two passes. {@link #transpileSource}
     * interleaves specifier rewriting, static module syntax stripping, and top-level name isolation
     * between them; none of those may be reordered, so a config that uses any of them keeps the two
     * call sequence. Source maps are excluded because the merged response carries the lowering
     * pass's (empty) map, while the two call path returns the transpile pass's map.</p>
     */
    private static boolean canMergeBridgeCalls(TypeScriptTranspileConfig config) {
        if (config.transformTarget() == null
                || config.rewriteTypeScriptModuleSpecifiers()
                || config.stripStaticModuleSyntax()
                || config.isolateTopLevelNames()
                || config.sourceMap() != Swc4jSourceMapOption.None
                || !isMergedBridgeCallEnabled()) {
            return false;
        }
        return Swc4jWasmRuntime.getInstance().supportsTranspileAndTransform();
    }

    private static boolean isMergedBridgeCallEnabled() {
        String value = System.getProperty(MERGED_BRIDGE_CALL_PROPERTY);
        return value == null || value.isBlank() || Boolean.parseBoolean(value.trim());
    }

    /**
     * Single crossing variant of {@link #swcTranspile} plus {@link #transformJavaScript}.
     *
     * <p>The bridge runs both passes with the exact option sets the two call sequence builds, so the
     * emitted body is identical; only the intermediate JavaScript stays inside the WASM runtime.
     * The {@code legacyFallback} path is not attempted here: this variant always has an SWC pass to
     * fall back to in {@link #transpileSource}.</p>
     */
    private static TypeScriptSourceTranspileResult mergedBridgeTranspile(
            String fileName,
            String swcSource,
            TypeScriptTranspileConfig config) throws Swc4jCoreException {
        Swc4jTransformOutput output = Swc4jWasmRuntime.getInstance().transpileAndTransform(
                swcSource,
                transpileOptions(fileName, config),
                transformOptions(fileName, config));
        return new TypeScriptSourceTranspileResult(output.getCode(), null, false, true);
    }

    public static TypeScriptSourceTranspileResult transpileKubeJsModuleBody(String fileName, String source) {
        TypeScriptSourceTranspileResult result = transpileSource(fileName, source, kubeJsModuleConfig());
        return new TypeScriptSourceTranspileResult(
                TypeScriptCommonJsModuleTransformer.transform(result.code()),
                result.sourceMap(),
                result.legacyFallback(),
                result.syntaxLoweringApplied());
    }

    /**
     * Exact pipeline used by {@link #transpileKubeJsModuleBody(String, String)}.
     *
     * <p>The disk cache fingerprints this configuration, so both the transpiler and the cache must
     * derive the effective config from this single place.</p>
     */
    public static TypeScriptTranspileConfig kubeJsModuleConfig() {
        return TypeScriptTranspileConfig.KUBEJS.toBuilder()
                .stripStaticModuleSyntax(false)
                .isolateTopLevelNames(false)
                .build();
    }

    public static boolean isTranspilableTypeScriptFile(String fileName) {
        String lower = lowerFileName(fileName);
        return (lower.endsWith(".ts") || lower.endsWith(".tsx") || lower.endsWith(".mts") || lower.endsWith(".cts"))
                && !isTypeScriptDeclarationFile(fileName);
    }

    public static boolean isTypeScriptDeclarationFile(String fileName) {
        String lower = lowerFileName(fileName);
        return lower.endsWith(".d.ts") || lower.endsWith(".d.mts") || lower.endsWith(".d.cts");
    }

    public static String outputFileName(String fileName) {
        String lower = lowerFileName(fileName);
        if (lower.endsWith(".mts")) {
            return fileName.substring(0, fileName.length() - ".mts".length()) + ".mjs";
        }
        if (lower.endsWith(".cts")) {
            return fileName.substring(0, fileName.length() - ".cts".length()) + ".cjs";
        }
        if (lower.endsWith(".tsx")) {
            return fileName.substring(0, fileName.length() - ".tsx".length()) + ".js";
        }
        if (lower.endsWith(".ts")) {
            return fileName.substring(0, fileName.length() - ".ts".length()) + ".js";
        }
        return fileName;
    }

    private static TypeScriptSourceTranspileResult swcTranspile(
            String fileName,
            String source,
            TypeScriptTranspileConfig config) throws Swc4jCoreException {
        Swc4jTranspileOutput output = SWC4J.transpile(source, transpileOptions(fileName, config));
        return new TypeScriptSourceTranspileResult(output.getCode(), output.getSourceMap(), false);
    }

    private static Swc4jTranspileOptions transpileOptions(String fileName, TypeScriptTranspileConfig config) {
        return new Swc4jTranspileOptions()
                .setSpecifier(toSpecifier(fileName))
                .setMediaType(mediaTypeFor(fileName))
                .setParseMode(config.parseMode())
                .setModuleKind(config.moduleKind())
                .setSourceMap(config.sourceMap())
                .setDecorators(config.decorators())
                .setKeepComments(config.keepComments());
    }

    private static String transformJavaScript(
            String fileName,
            String source,
            TypeScriptTranspileConfig config) throws Swc4jCoreException {
        Swc4jTransformOutput output = SWC4J.transform(source, transformOptions(fileName, config));
        return output.getCode();
    }

    private static Swc4jTransformOptions transformOptions(String fileName, TypeScriptTranspileConfig config) {
        return new Swc4jTransformOptions()
                .setSpecifier(toSpecifier(outputFileName(fileName)))
                .setMediaType(Swc4jMediaType.JavaScript)
                .setParseMode(config.stripStaticModuleSyntax() ? Swc4jParseMode.Script : config.parseMode())
                .setSourceMap(Swc4jSourceMapOption.None)
                .setKeepComments(config.keepComments())
                .setMinify(false)
                .setTarget(config.transformTarget());
    }

    private static String legacyTranspile(String fileName, String source, TypeScriptTranspileConfig config) {
        String transformed = TypeScriptDeclarationStripper.stripImportStatements(source);
        transformed = TypeScriptDeclarationStripper.removeTypeOnlyDeclarations(transformed);
        transformed = TypeScriptEnumTransformer.transformEnums(transformed);
        transformed = TypeScriptDeclarationStripper.stripExportStatements(transformed);
        transformed = TypeScriptSyntaxStripper.strip(transformed);
        if (config.rewriteTypeScriptModuleSpecifiers()) {
            transformed = rewriteTypeScriptModuleSpecifiers(transformed);
        }
        if (config.isolateTopLevelNames()) {
            transformed = TypeScriptFileScopeIsolator.isolate(fileName, transformed);
        }
        return transformed;
    }

    private static Swc4jMediaType mediaTypeFor(String fileName) {
        String lower = lowerFileName(fileName);
        if (lower.endsWith(".tsx")) {
            return Swc4jMediaType.Tsx;
        }
        if (lower.endsWith(".mts")) {
            return Swc4jMediaType.Mts;
        }
        if (lower.endsWith(".cts")) {
            return Swc4jMediaType.Cts;
        }
        return Swc4jMediaType.TypeScript;
    }

    private static String rewriteTypeScriptModuleSpecifiers(String source) {
        Matcher matcher = MODULE_SPECIFIER_PATTERN.matcher(source);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String rewritten = rewriteModuleSpecifier(matcher.group(3));
            if (rewritten.equals(matcher.group(3))) {
                continue;
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(
                    matcher.group(1) + matcher.group(2) + rewritten + matcher.group(4)));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String rewriteModuleSpecifier(String specifier) {
        if (!specifier.startsWith(".") && !specifier.startsWith("/")) {
            return specifier;
        }

        int suffixStart = specifier.length();
        int query = specifier.indexOf('?');
        int hash = specifier.indexOf('#');
        if (query >= 0) {
            suffixStart = Math.min(suffixStart, query);
        }
        if (hash >= 0) {
            suffixStart = Math.min(suffixStart, hash);
        }

        String path = specifier.substring(0, suffixStart);
        String suffix = specifier.substring(suffixStart);
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".d.ts") || lower.endsWith(".d.mts") || lower.endsWith(".d.cts")) {
            return specifier;
        }
        if (lower.endsWith(".mts")) {
            return path.substring(0, path.length() - ".mts".length()) + ".mjs" + suffix;
        }
        if (lower.endsWith(".cts")) {
            return path.substring(0, path.length() - ".cts".length()) + ".cjs" + suffix;
        }
        if (lower.endsWith(".tsx")) {
            return path.substring(0, path.length() - ".tsx".length()) + ".js" + suffix;
        }
        if (lower.endsWith(".ts")) {
            return path.substring(0, path.length() - ".ts".length()) + ".js" + suffix;
        }
        return specifier;
    }

    private static URL toSpecifier(String fileName) {
        try {
            return Path.of(fileName).toUri().toURL();
        } catch (InvalidPathException | MalformedURLException e) {
            try {
                return new URL("file", "", "/" + fileName.replace('\\', '/').replace(" ", "%20"));
            } catch (MalformedURLException malformedURLException) {
                throw new TypeScriptTranspileException("Invalid TypeScript source path '" + fileName + "'", e);
            }
        }
    }

    private static String lowerFileName(String fileName) {
        return fileName.toLowerCase(Locale.ROOT);
    }

    private static String normalizeLineEndings(String source) {
        return source.replace("\r\n", "\n").replace('\r', '\n');
    }
}
