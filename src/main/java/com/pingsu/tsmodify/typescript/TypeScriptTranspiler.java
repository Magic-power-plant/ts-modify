package com.pingsu.tsmodify.typescript;

import com.pingsu.transpiler.CommentMode;
import com.pingsu.transpiler.JavaScriptTarget;
import com.pingsu.transpiler.LexicalMode;
import com.pingsu.transpiler.MediaType;
import com.pingsu.transpiler.ModuleKind;
import com.pingsu.transpiler.OutputFormat;
import com.pingsu.transpiler.OutputPreset;
import com.pingsu.transpiler.SourceMapMode;
import com.pingsu.transpiler.TranspileOptions;
import com.pingsu.transpiler.TranspileReport;
import com.pingsu.tsmodify.typescript.syntax.TypeScriptDeclarationStripper;
import com.pingsu.tsmodify.typescript.syntax.TypeScriptAutomaticSemicolonGuard;
import com.pingsu.tsmodify.typescript.syntax.TypeScriptCommonJsModuleTransformer;
import com.pingsu.tsmodify.typescript.syntax.TypeScriptEnumTransformer;
import com.pingsu.tsmodify.typescript.syntax.TypeScriptFileScopeIsolator;
import com.pingsu.tsmodify.typescript.syntax.TypeScriptRhinoCallbackAdapter;
import com.pingsu.tsmodify.typescript.syntax.TypeScriptSyntaxStripper;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TypeScriptTranspiler {
    private static final Pattern MODULE_SPECIFIER_PATTERN = Pattern.compile(
            "(\\bfrom\\s*|\\bimport\\s*\\(\\s*|\\brequire\\s*\\(\\s*|\\bimport\\s+)(['\"])([^'\"]+)(['\"])");
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
        return transpileSource(fileName, source, config, false);
    }

    private static TypeScriptSourceTranspileResult transpileSource(
            String fileName, String source, TypeScriptTranspileConfig config, boolean moduleBody) {
        if (!isTranspilableTypeScriptFile(fileName)) {
            return new TypeScriptSourceTranspileResult(source, null, false);
        }

        String prepared;
        try {
            prepared = config.guardAutomaticSemicolonHazards()
                    ? TypeScriptAutomaticSemicolonGuard.guard(source) : source;
        } catch (Throwable failure) {
            throw TypeScriptErrorReporter.transpileFailure(fileName, source, "TypeScript pre-processing", failure);
        }
        TranspileReport report;
        try {
            // Preserve syntax until the host has processed modules and file-local bindings.
            report = TranslanguageCompiler.compile(fileName, prepared, options(fileName, config)
                    .target(JavaScriptTarget.PRESERVE)
                    .mediaType(mediaTypeFor(fileName))
                    .module(config.moduleKind())
                    .sourceMap(config.sourceMap())
                    .build());
        } catch (Throwable failure) {
            if (config.legacyFallback()) {
                try {
                    return new TypeScriptSourceTranspileResult(legacyTranspile(fileName, source, config), null, true);
                } catch (Throwable fallbackFailure) {
                    fallbackFailure.addSuppressed(failure);
                    throw TypeScriptErrorReporter.transpileFailure(fileName, source, "legacy fallback", fallbackFailure);
                }
            }
            throw TypeScriptErrorReporter.transpileFailure(fileName, source, "Translanguage transpilation", failure);
        }

        try {
            String code = report.result().javaScript();
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
            // ES5 cannot contain ESM. Keep the established runtime import/default/circular semantics
            // by applying our CommonJS adapter before the JavaScript lowering pass.
            if (moduleBody) {
                code = TypeScriptCommonJsModuleTransformer.transform(code);
            }
            if (config.transformTarget() != null) {
                code = TranslanguageCompiler.compile(fileName, code, options(fileName, config)
                        .target(config.transformTarget())
                        .mediaType(MediaType.JS)
                        .module(moduleBody || config.stripStaticModuleSyntax() ? ModuleKind.SCRIPT : config.moduleKind())
                        .build()).result().javaScript();
            }
            if (config.lowerRuntimeSyntax()) {
                code = TypeScriptSyntaxStripper.stripPostEs2015Syntax(code);
                code = TypeScriptRhinoCallbackAdapter.adapt(fileName, code);
            }
            String sourceMap = config.sourceMap() == SourceMapMode.EXTERNAL ? report.sourceMap().orElse(null) : null;
            return new TypeScriptSourceTranspileResult(code, sourceMap, false, config.transformTarget() != null);
        } catch (Throwable failure) {
            throw TypeScriptErrorReporter.transpileFailure(fileName, source, "TypeScript post-processing", failure);
        }
    }

    public static TypeScriptSourceTranspileResult transpileKubeJsModuleBody(String fileName, String source) {
        return transpileSource(fileName, source, kubeJsModuleConfig(), true);
    }

    private static TranspileOptions.Builder options(String fileName, TypeScriptTranspileConfig config) {
        TranspileOptions.Builder options = TranspileOptions.builder()
                .decorators(config.decorators())
                .comments(config.keepComments() ? CommentMode.ALL : CommentMode.NONE)
                .format(OutputFormat.READABLE)
                .sourceMapFile(outputFileName(fileName).replace('\\', '/'));
        if (config.lowerRuntimeSyntax()) {
            options.preset(OutputPreset.KUBEJS_RHINO_2001_2_3_BUILD_6)
                    .lexicalMode(LexicalMode.LOOSE);
        }
        return options;
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

    private static MediaType mediaTypeFor(String fileName) {
        String lower = lowerFileName(fileName);
        if (lower.endsWith(".tsx")) {
            return MediaType.TSX;
        }
        if (lower.endsWith(".mts")) {
            return MediaType.MTS;
        }
        if (lower.endsWith(".cts")) {
            return MediaType.CTS;
        }
        return MediaType.TS;
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

    private static String lowerFileName(String fileName) {
        return fileName.toLowerCase(Locale.ROOT);
    }

    private static String normalizeLineEndings(String source) {
        return source.replace("\r\n", "\n").replace('\r', '\n');
    }
}
