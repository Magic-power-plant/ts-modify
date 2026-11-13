package com.pingsu.tsmodify.typescript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pingsu.transpiler.SourceMapMode;
import dev.latvian.mods.kubejs.event.IEventHandler;
import dev.latvian.mods.rhino.Context;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class TranslanguageRuntimeTest {
    private static final TypeScriptTranspileConfig RUNTIME = TypeScriptTranspileConfig.KUBEJS.toBuilder()
            .isolateTopLevelNames(false).build();

    @Test
    void usesThePinnedPureJavaArtifactWithoutTheOldRuntime() throws Exception {
        assertEquals("2.0.6", TranslanguageCompiler.version());
        assertEquals(TranslanguageCompiler.version(),
                com.pingsu.transpiler.TypeScriptTranspiler.class.getPackage().getImplementationVersion());
        byte[] artifact = Files.readAllBytes(Path.of("docs/Translanguage-2.0.6.jar"));
        assertEquals(TranslanguageCompiler.sha256(),
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(artifact)));
        assertNull(getClass().getResource("/swc4j/swc4j_bridge.wasm"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName("com.caoccao.javet.swc4j.Swc4j"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName("com.dylibso.chicory.runtime.Instance"));
    }

    @Test
    void executesLoopInitializersAndLoopClosuresOnRhino() {
        assertEquals("3:2", evaluate("""
                let callbacks = [];
                for (let i = 0; i < 3; i++) { callbacks.push(() => i); }
                let total = 0;
                for (let a = 0, b = a + 1; a < 3; a++) { total += b; }
                var result = total + ":" + (callbacks[0]() + callbacks[2]());
                """));
    }

    @Test
    void convertsLoweredArrowsToKubeJsEventHandlers() throws Throwable {
        String code = TypeScriptTranspiler.transpileKubeJsModuleBody("startup_scripts/event.ts", """
                var handler = (event: any) => "registered";
                """).code();
        Context context = Context.enter();
        var scope = context.initStandardObjects();
        Object callback = context.evaluateString(scope, code + "\nhandler;", "event.ts", 1, null);
        context.setTopCall(scope);
        IEventHandler handler;
        try {
            handler = (IEventHandler) Context.jsToJava(context, callback, IEventHandler.class);
        } finally {
            context.setTopCall(null);
        }
        assertEquals("registered", handler.onEvent(null));
    }

    @Test
    void executesClassesInheritanceAndFieldsOnRhino() {
        assertEquals("7:1", evaluate("""
                class Base { constructor(public value: number) {} read() { return this.value; } }
                class Child extends Base {
                  static count = 0;
                  extra = 2;
                  constructor(value: number) { super(value); Child.count++; }
                  read() { return super.read() + this.extra; }
                }
                var result = new Child(5).read() + ":" + Child.count;
                """));
    }

    @Test
    void executesLegacyDecoratedClassesWithoutChangingReadOnlyNames() {
        assertEquals("ok", evaluate("""
                function mark(value: any) { value.label = "ok"; }
                @mark
                class Decorated {}
                var result = (Decorated as any).label;
                """));
    }

    @Test
    void lowersGeneratorsAsyncDeclarationsEnumsAndNamespaces() {
        assertEquals("add:3:1:2", evaluate("""
                enum Mode { Add = "add", Remove = "remove" }
                namespace Values { export const base: number = 3; }
                async function load(): Promise<number> { return await Promise.resolve(1); }
                function* range(): Generator<number> { yield 1; yield 2; }
                var iterator = range();
                var result = Mode.Add + ":" + Values.base + ":" + iterator.next().value + ":" + iterator.next().value;
                """));
    }

    @Test
    void retainsStructuredDiagnosticsAndOriginalSourceLocation() {
        TypeScriptTranspileException failure = assertThrows(TypeScriptTranspileException.class,
                () -> TypeScriptTranspiler.transpileSource("broken.ts", "// first line\nconst broken: number = ;", RUNTIME));
        assertEquals("Translanguage transpilation", failure.context().phase());
        assertEquals(2, failure.context().line());
        assertTrue(failure.context().column() > 0);
        var cause = assertInstanceOf(TranslanguageCompiler.CompilationFailure.class, failure.getCause());
        assertFalse(cause.diagnostics().isEmpty());
        assertTrue(failure.context().sourceExcerpt().contains("const broken"));
    }

    @Test
    void rejectsBigIntForRhinoBeforeExecution() {
        var failure = assertThrows(TypeScriptTranspileException.class,
                () -> TypeScriptTranspiler.transpileSource("bigint.ts", "const value = 1n;", RUNTIME));
        assertTrue(failure.getMessage().toLowerCase().contains("bigint"), failure.getMessage());
    }

    @Test
    void supportsExplicitMediaTypesAndDeclarationExclusions() {
        for (String extension : List.of("ts", "mts", "cts")) {
            String code = TypeScriptTranspiler.transpileKubeJsModuleBody("module." + extension,
                    "export const value: number = 3;").code();
            Context context = Context.enter();
            var scope = context.initStandardObjects();
            assertEquals(3, ((Number) context.evaluateString(scope,
                    "var exports = {};\n" + code + "\nexports.value;", "module.js", 1, null)).intValue());
        }
        for (String extension : List.of("d.ts", "d.mts", "d.cts", "js")) {
            String source = "declare const unchanged: unknown;";
            assertEquals(source, TypeScriptTranspiler.transpileSource("module." + extension, source, RUNTIME).code());
        }
        assertEquals("div", evaluate("component.tsx", """
                var React = { createElement: function (tag, props, child) { return tag; } };
                var result = <div>{1}</div>;
                """));
    }

    @Test
    void preservesProjectModulesCommentsAndSupportsSourceMaps() {
        var external = TypeScriptTranspiler.transpileSource("module.mts",
                "// retained\nexport const value: number = 3;", TypeScriptTranspileConfig.PROJECT.toBuilder()
                        .sourceMap(SourceMapMode.EXTERNAL).build());
        assertTrue(external.code().contains("export"));
        assertTrue(external.code().contains("// retained"));
        assertNotNull(external.sourceMap());
        assertTrue(external.sourceMap().contains("module.mts"));
        var inline = TypeScriptTranspiler.transpileSource("module.ts", "export const value: number = 3;",
                TypeScriptTranspileConfig.PROJECT.toBuilder().sourceMap(SourceMapMode.INLINE).build());
        assertNull(inline.sourceMap());
        assertTrue(inline.code().contains("sourceMappingURL=data:"), inline.code());
        var noComments = TypeScriptTranspiler.transpileSource("module.ts", "// removed\nconst value: number = 3;",
                TypeScriptTranspileConfig.PROJECT.toBuilder().keepComments(false).build());
        assertFalse(noComments.code().contains("removed"));
    }

    @Test
    void compilesIndependentSessionsConcurrently() throws Exception {
        var executor = Executors.newFixedThreadPool(4);
        try {
            List<Callable<String>> tasks = java.util.stream.IntStream.range(0, 16)
                    .mapToObj(index -> (Callable<String>) () -> TypeScriptTranspiler.transpileKubeJsModuleBody(
                            "module" + index + ".ts", "export const value: number = " + index + ";").code()).toList();
            var results = executor.invokeAll(tasks);
            for (int index = 0; index < results.size(); index++) {
                assertEquals(TypeScriptTranspiler.transpileKubeJsModuleBody("module" + index + ".ts",
                        "export const value: number = " + index + ";").code(), results.get(index).get());
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static String evaluate(String source) {
        return evaluate("runtime.ts", source);
    }

    private static String evaluate(String name, String source) {
        String code = TypeScriptTranspiler.transpileSource(name, source, RUNTIME).code();
        Context context = Context.enter();
        var scope = context.initStandardObjects();
        return String.valueOf(context.evaluateString(scope, code + "\nresult;", name, 1, null));
    }
}
