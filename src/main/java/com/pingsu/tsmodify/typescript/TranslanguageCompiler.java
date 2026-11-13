package com.pingsu.tsmodify.typescript;

import com.pingsu.transpiler.Diagnostic;
import com.pingsu.transpiler.TranspileOptions;
import com.pingsu.transpiler.TranspileReport;
import com.pingsu.transpiler.internal.parser.RecursiveDescentParser;
import com.pingsu.transpiler.internal.parser.SourceParser;
import com.pingsu.transpiler.syntax.Program;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/** The engine boundary: per-call compiler sessions, structured diagnostics, and cheap counters. */
public final class TranslanguageCompiler {
    private static final Properties IDENTITY = loadIdentity();
    private static final AtomicLong INVOCATIONS = new AtomicLong();

    private TranslanguageCompiler() {
    }

    public static String version() {
        return IDENTITY.getProperty("version");
    }

    public static String sha256() {
        return IDENTITY.getProperty("sha256");
    }

    public static long invocationCount() {
        return INVOCATIONS.get();
    }

    static TranspileReport compile(String fileName, String source, TranspileOptions options) {
        // The library owns each compilation session and its lifecycle.
        var compiler = new com.pingsu.transpiler.TypeScriptTranspiler(options);
        INVOCATIONS.incrementAndGet();
        TranspileReport report = compiler.transpileDetailed(fileName.replace('\\', '/'), source);
        if (!report.result().successful()) {
            throw new CompilationFailure(report.result().diagnostics());
        }
        return report;
    }

    /** Keeps the pinned engine's parser API behind the same boundary as compilation. */
    public static Program parseJavaScript(String fileName, String source) {
        var parsed = new RecursiveDescentParser().parse(
                fileName.replace('\\', '/'), source, SourceParser.Language.JAVASCRIPT);
        if (!parsed.successful()) {
            throw new CompilationFailure(parsed.diagnostics());
        }
        return parsed.program();
    }

    private static Properties loadIdentity() {
        Properties properties = new Properties();
        try (InputStream input = TranslanguageCompiler.class.getResourceAsStream("/tsmodify/translanguage.properties")) {
            if (input == null) {
                throw new IllegalStateException("Missing Translanguage build identity");
            }
            properties.load(input);
            if (properties.getProperty("version") == null || properties.getProperty("sha256") == null) {
                throw new IllegalStateException("Incomplete Translanguage build identity");
            }
            return properties;
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read Translanguage build identity", failure);
        }
    }

    /** Retains every engine diagnostic instead of reducing the failure to an unlocated string. */
    public static final class CompilationFailure extends RuntimeException {
        private final List<Diagnostic> diagnostics;

        private CompilationFailure(List<Diagnostic> diagnostics) {
            super(diagnostics.stream().map(diagnostic -> diagnostic.sourceName() + ":"
                    + diagnostic.line() + ":" + diagnostic.column() + " [" + diagnostic.code()
                    + "] " + diagnostic.message()).collect(Collectors.joining(System.lineSeparator())));
            this.diagnostics = List.copyOf(diagnostics);
        }

        public List<Diagnostic> diagnostics() {
            return diagnostics;
        }
    }
}
