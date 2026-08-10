package com.pingsu.tsmodify.typescript.rhino;

import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.EvaluatorException;
import dev.latvian.mods.rhino.Function;
import dev.latvian.mods.rhino.Scriptable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RhinoScriptCompilationCacheTest {
    @BeforeEach
    void clearCacheBeforeTest() {
        RhinoScriptCompilationCache.clearForTests();
    }

    @AfterEach
    void clearCacheAfterTest() {
        RhinoScriptCompilationCache.clearForTests();
    }

    @Test
    void cachedScriptExecutesWithEachContextsOwnScope() {
        String source = """
                var counter = typeof counter === "undefined" ? 0 : counter;
                counter += 1;
                var exported = (function(value) {
                    return function() { return value + counter; };
                })(40);
                var result = exported();
        """;

        EvaluatedScope firstScope = execute(source, "reload.js");
        Function firstExport = assertScopeState(firstScope);

        EvaluatedScope secondScope = execute(source, "reload.js");
        Function secondExport = assertScopeState(secondScope);

        assertNotSame(firstExport.getParentScope(), secondExport.getParentScope());
        assertTrue(RhinoScriptCompilationCache.containsForTests(
                secondScope.context(), source, "reload.js", 1));
    }

    @Test
    void sourceOrCompilerModeChangeCreatesAnotherCompiledEntry() {
        String firstSource = "var result = 1;";
        String secondSource = "var result = 2;";
        execute(firstSource, "reload.js");
        EvaluatedScope secondScope = execute(secondSource, "reload.js");

        assertFalse(RhinoScriptCompilationCache.containsForTests(
                secondScope.context(), firstSource, "reload.js", 1));
        assertTrue(RhinoScriptCompilationCache.containsForTests(
                secondScope.context(), secondSource, "reload.js", 1));
        assertEquals(1, RhinoScriptCompilationCache.entryCountForTests());

        Context observerContext = Context.enter();
        observerContext.setGenerateObserverCount(true);
        Scriptable observerScope = observerContext.initStandardObjects();
        RhinoScriptCompilationCache.evaluate(observerContext, observerScope, secondSource, "reload.js", 1, null);

        assertTrue(RhinoScriptCompilationCache.containsForTests(
                observerContext, secondSource, "reload.js", 1));
        assertEquals(2, RhinoScriptCompilationCache.entryCountForTests());
    }

    @Test
    void compileReusesBytecodeAcrossContextsWithoutReusingScopeState() {
        String source = "var result = typeof result === 'undefined' ? 42 : -1;";
        Context firstContext = Context.enter();
        Scriptable firstScope = firstContext.initStandardObjects();
        var firstScript = RhinoScriptCompilationCache.compile(
                firstContext,
                source,
                "compiled-reload.js",
                1,
                null);
        firstScript.exec(firstContext, firstScope);
        assertEquals(42, ((Number) firstScope.get(firstContext, "result", firstScope)).intValue());

        Context secondContext = Context.enter();
        Scriptable secondScope = secondContext.initStandardObjects();
        var secondScript = RhinoScriptCompilationCache.compile(
                secondContext,
                source,
                "compiled-reload.js",
                1,
                null);

        assertSame(firstScript, secondScript);
        secondScript.exec(secondContext, secondScope);
        assertEquals(42, ((Number) secondScope.get(secondContext, "result", secondScope)).intValue());
        assertEquals(1, RhinoScriptCompilationCache.entryCountForTests());
    }

    @Test
    void cachedScriptCanExecuteInConcurrentContexts() throws Exception {
        String source = """
                var result = (function() {
                    var values = [40, 2];
                    return { value: values[0] + values[1] }.value;
                })();
                """;
        execute(source, "concurrent-reload.js");

        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Integer>> work = new ArrayList<>();
            for (int index = 0; index < 16; index++) {
                work.add(() -> {
                    EvaluatedScope evaluatedScope = execute(source, "concurrent-reload.js");
                    Object result = evaluatedScope.scope().get(evaluatedScope.context(), "result", evaluatedScope.scope());
                    return ((Number) result).intValue();
                });
            }
            for (Future<Integer> result : executor.invokeAll(work)) {
                assertEquals(42, result.get().intValue());
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void failedCompilationDoesNotPopulateTheCache() {
        Context context = Context.enter();
        Scriptable scope = context.initStandardObjects();

        assertThrows(EvaluatorException.class, () -> RhinoScriptCompilationCache.evaluate(
                context,
                scope,
                "var = invalid;",
                "invalid-reload.js",
                1,
                null));

        assertEquals(0, RhinoScriptCompilationCache.entryCountForTests());
    }

    @Test
    void securityDomainUsesTheOriginalRhinoEvaluationPath() {
        Context context = Context.enter();
        Scriptable scope = context.initStandardObjects();

        assertThrows(IllegalArgumentException.class, () -> context.evaluateString(
                scope,
                "var result = 42;",
                "security-domain-reload.js",
                1,
                new Object()));
        assertThrows(IllegalArgumentException.class, () -> RhinoScriptCompilationCache.evaluate(
                context,
                scope,
                "var result = 42;",
                "security-domain-reload.js",
                1,
                new Object()));

        assertEquals(0, RhinoScriptCompilationCache.entryCountForTests());
    }

    @Test
    void cacheUsesTheOriginalResultForOrdinarySource() {
        Context context = Context.enter();
        Scriptable scope = context.initStandardObjects();

        RhinoScriptCompilationCache.evaluate(
                context,
                scope,
                "var result = 42;",
                "ordinary-reload.js",
                1,
                null);

        Object result = scope.get(context, "result", scope);
        assertEquals(42, ((Number) result).intValue());
        assertEquals(1, RhinoScriptCompilationCache.entryCountForTests());
    }

    private static EvaluatedScope execute(String source, String sourceName) {
        Context context = Context.enter();
        Scriptable scope = context.initStandardObjects();
        RhinoScriptCompilationCache.evaluate(context, scope, source, sourceName, 1, null);
        return new EvaluatedScope(context, scope);
    }

    private static Function assertScopeState(EvaluatedScope evaluatedScope) {
        Context context = evaluatedScope.context();
        Scriptable scope = evaluatedScope.scope();
        Object result = scope.get(context, "result", scope);
        Function exported = (Function) scope.get(context, "exported", scope);

        assertEquals(41, ((Number) result).intValue());
        assertEquals(41, ((Number) exported.call(context, scope, scope, new Object[0])).intValue());
        return exported;
    }

    private record EvaluatedScope(Context context, Scriptable scope) {
    }
}
