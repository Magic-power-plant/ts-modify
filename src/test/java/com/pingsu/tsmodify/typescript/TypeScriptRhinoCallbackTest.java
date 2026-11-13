package com.pingsu.tsmodify.typescript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pingsu.tsmodify.typescript.syntax.TypeScriptRhinoCallbackAdapter;
import dev.latvian.mods.kubejs.event.IEventHandler;
import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.ScriptableObject;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.Test;

class TypeScriptRhinoCallbackTest {
    @Test
    void registersCallbacksThatCaptureOuterLexicalBindings() throws Throwable {
        String source = """
                const labels = ["item", "tooltip"];
                let suffix = "before";
                StartupEvents.registry("item", event => labels[0] + ":" + suffix);
                StartupEvents.registry("tooltip", event => labels[1] + ":" + suffix);
                suffix = "after";
                """;
        for (String code : List.of(
                TypeScriptTranspiler.transpileSource("captures.ts", source,
                        TypeScriptTranspileConfig.KUBEJS).code(),
                TypeScriptTranspiler.transpileKubeJsModuleBody("captures.ts", source).code())) {
            Context context = Context.enter();
            var scope = context.initStandardObjects();
            EventRegistrar registrar = new EventRegistrar();
            ScriptableObject.putProperty(scope, "StartupEvents", Context.javaToJS(context, registrar, scope), context);
            try {
                context.evaluateString(scope, code, "captures.ts", 1, null);
            } catch (Throwable error) {
                throw new AssertionError(code, error);
            }
            assertEquals(2, registrar.handlers.size());
            assertEquals("item:after", registrar.handlers.get(0).onEvent(null));
            assertEquals("tooltip:after", registrar.handlers.get(1).onEvent(null));
        }
    }

    @Test
    void registersAndInvokesStartupHandlersThroughJavaMethodsInBothRuntimePaths() throws Throwable {
        String source = """
                StartupEvents.registry("item", (event: any) => "item");
                StartupEvents.registry("rite", event => { return "rite"; });
                StartupEvents.registry("shape", function(event: any) { return "shape"; });
                """;
        for (String code : List.of(
                TypeScriptTranspiler.transpileSource("startup_scripts/registry.ts", source,
                        TypeScriptTranspileConfig.KUBEJS).code(),
                TypeScriptTranspiler.transpileKubeJsModuleBody("startup_scripts/registry.ts", source).code())) {
            Context context = Context.enter();
            var scope = context.initStandardObjects();
            EventRegistrar registrar = new EventRegistrar();
            ScriptableObject.putProperty(scope, "StartupEvents", Context.javaToJS(context, registrar, scope), context);
            context.evaluateString(scope, code, "registry.ts", 1, null);
            assertEquals(List.of("item", "rite", "shape"), registrar.names);
            List<Object> values = new ArrayList<>();
            for (IEventHandler handler : registrar.handlers) {
                values.add(handler.onEvent(null));
            }
            assertEquals(List.of("item", "rite", "shape"), values);
        }
    }

    @Test
    void preservesLexicalThisArgumentsNestedClosuresAndFunctionArity() {
        assertEquals("kept:outer:inner:2", evaluate("""
                function create(label: string) {
                  return (event: any, suffix: string) => {
                    const nested = () => this.name + ":" + arguments[0] + ":" + suffix;
                    return nested.call({ name: "wrong" });
                  };
                }
                var callback = create.call({ name: "kept" }, "outer");
                var result = callback.call({ name: "wrong" }, null, "inner") + ":" + callback.length;
                """));
    }

    @Test
    void invokesNestedJavaCallbacksWithCapturedBindingsAndLoopIterations() throws Throwable {
        String source = """
                const values = [3, 5];
                let offset = 1;
                for (let i = 0; i < 3; i++) {
                  StartupEvents.registry("nested" + i, event => {
                    const local = values[i % values.length];
                    return StartupEvents.apply((ignored: any) => local + i + offset);
                  });
                }
                offset = 2;
                """;
        Context context = Context.enter();
        var scope = context.initStandardObjects();
        EventRegistrar registrar = new EventRegistrar();
        ScriptableObject.putProperty(scope, "StartupEvents", Context.javaToJS(context, registrar, scope), context);
        String code = TypeScriptTranspiler.transpileKubeJsModuleBody("nested.ts", source).code();
        context.evaluateString(scope, code, "nested.ts", 1, null);
        assertEquals(List.of("nested0", "nested1", "nested2"), registrar.names);
        assertEquals(5, ((Number) registrar.handlers.get(0).onEvent(null)).intValue());
        assertEquals(8, ((Number) registrar.handlers.get(1).onEvent(null)).intValue());
        assertEquals(7, ((Number) registrar.handlers.get(2).onEvent(null)).intValue());
    }

    @Test
    void preservesCapturedCallbackArityAndLexicalThisAcrossFactoryEvaluation() {
        assertEquals("5:2:true:kept", evaluate("""
                const a = 1, b = 2, c = 3;
                function create() {
                  return (x: number, y: number) => this.label + ":" + (a + b + c + x + y);
                }
                var callback = create.call({ label: "kept" });
                var calls = 0;
                const marker = { count: 4 };
                var nested = (() => { calls++; return () => ++marker.count; })();
                var rejected = false;
                try { new (nested as any)(); } catch (error) { rejected = error instanceof TypeError; }
                var result = nested() + ":" + callback.length + ":" + (rejected && calls === 1)
                    + ":" + callback.call({ label: "wrong" }, 0, 0).split(":")[0];
                """));
    }

    @Test
    void preservesDefaultAndRestParametersReturnValuesAndThrownObjects() {
        assertEquals("7:true", evaluate("""
                var add = (value: number = 2, ...rest: number[]) => value + rest[0];
                var marker = { message: "original" };
                var fail = () => { throw marker; };
                var same = false;
                try { fail(); } catch (error) { same = error === marker; }
                var result = add(undefined, 5) + ":" + same;
                """));
    }

    @Test
    void continuesToRejectArrowConstruction() {
        assertEquals("true", evaluate("""
                var callback = () => 3;
                var rejected = false;
                try { new (callback as any)(); } catch (error) { rejected = error instanceof TypeError; }
                var result = String(rejected);
                """));
    }

    @Test
    void respectsRegexCommentsStringsTemplatesNestingAndCrLf() {
        String source = """
                // function $tl$callable$9() {}.bind(this) stays a comment
                var text = "((function $tl$callable$9() {})).bind(this)";
                var callback = () => {
                  /* }))).bind(this) */
                  var regex = /[(){}]|\\.bind\\(this\\)/;
                  return `value:` + (() => regex.test(")") + ":" + text)();
                };
                var result = callback();
                """.replace("\n", "\r\n");
        assertEquals("value:true:((function $tl$callable$9() {})).bind(this)", evaluate(source));

        String emitted = "// unchanged\r\nvar cb = ((function $tl$callable$0(a) {\r\n"
                + "return /[(){}]/.test(a);\r\n})).bind(this);\r\n";
        String adapted = TypeScriptRhinoCallbackAdapter.adapt("crlf.js", emitted);
        assertEquals(emitted.chars().filter(c -> c == '\r').count(),
                adapted.chars().filter(c -> c == '\r').count());
        assertEquals(emitted.chars().filter(c -> c == '\n').count(),
                adapted.chars().filter(c -> c == '\n').count());
    }

    @Test
    void leavesUserBindExpressionsAndLookalikeTextUntouched() {
        String source = """
                var marker = "$tl$callable$";
                var user = (function ordinary(value) { return this.value + value; }).bind(this);
                var generatedNameOnly = (function $tl$callable$0() {}).bind({});
                var lookalike = "((function $tl$callable$1() {})).bind(this)";
                var regex = /function $tl$callable$2\\(\\) {}.bind\\(this\\)/;
                var unrelated = (function() {
                  var inner = function $tl$callable$3() {};
                  return function ordinary(value) { return value; };
                })().bind(this);
                var nestedOnly = (function() {
                  return { value: function $tl$callable$4() {} };
                })().bind(this);
                """;
        assertEquals(source, TypeScriptRhinoCallbackAdapter.adapt("user.js", source));
    }

    @Test
    void keepsProjectModeAndJavaScriptInputOutsideTheAdapter() {
        String source = "const callback = (event: any) => event;";
        String project = TypeScriptTranspiler.transpileSource("project.ts", source,
                TypeScriptTranspileConfig.PROJECT).code();
        assertTrue(project.contains("=>"), project);
        assertFalse(project.contains("$tsmodify$bound"), project);
        String javascript = "var cb = ((function $tl$callable$0() {})).bind(this);";
        assertEquals(javascript, TypeScriptTranspiler.transpileSource("user.js", javascript,
                TypeScriptTranspileConfig.KUBEJS).code());
    }

    @Test
    void retainsStructuredParserFailuresAtTheEngineBoundary() {
        var failure = assertThrows(TranslanguageCompiler.CompilationFailure.class,
                () -> TranslanguageCompiler.parseJavaScript("broken.js", "var broken = ;"));
        assertFalse(failure.diagnostics().isEmpty());
        assertEquals("broken.js", failure.diagnostics().get(0).sourceName());
    }

    private static String evaluate(String source) {
        String code = TypeScriptTranspiler.transpileKubeJsModuleBody("callbacks.ts", source).code();
        Context context = Context.enter();
        var scope = context.initStandardObjects();
        return String.valueOf(context.evaluateString(scope, code + "\nresult;", "callbacks.ts", 1, null));
    }

    public static final class EventRegistrar {
        private final List<String> names = new ArrayList<>();
        private final List<IEventHandler> handlers = new ArrayList<>();

        public void registry(String name, IEventHandler handler) {
            names.add(name);
            handlers.add(handler);
        }

        public int apply(ToIntFunction<Object> callback) {
            return callback.applyAsInt(null);
        }
    }
}
