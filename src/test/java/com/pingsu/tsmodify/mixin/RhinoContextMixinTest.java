package com.pingsu.tsmodify.mixin;

import com.pingsu.tsmodify.typescript.module.TypeScriptModuleRegistry;
import com.pingsu.tsmodify.typescript.module.TypeScriptModuleRuntime;
import com.pingsu.tsmodify.typescript.module.TypeScriptScriptDomain;
import com.pingsu.tsmodify.typescript.rhino.RhinoModuleFactoryCompiler;
import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.ErrorReporter;
import dev.latvian.mods.rhino.Evaluator;
import dev.latvian.mods.rhino.Function;
import dev.latvian.mods.rhino.NativeObject;
import dev.latvian.mods.rhino.Scriptable;
import java.lang.reflect.Method;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RhinoContextMixinTest {
    @AfterEach
    void clearModuleRegistry() {
        TypeScriptModuleRegistry.clear();
    }

    @Test
    void compileFunctionInvokerMatchesPinnedRhinoSignature() throws NoSuchMethodException {
        Method target = contextCompileFunction();
        Method invoker = RhinoContextMixin.class.getDeclaredMethod(
                "tsmodify$compileFunction",
                target.getParameterTypes());

        assertEquals(Function.class, target.getReturnType());
        assertEquals(target.getReturnType(), invoker.getReturnType());
        assertArrayEquals(target.getParameterTypes(), invoker.getParameterTypes());
        assertNotNull(invoker.getAnnotation(org.spongepowered.asm.mixin.gen.Invoker.class));
    }

    @Test
    void pinnedRhinoCompilerCreatesAnonymousModuleFactory() throws ReflectiveOperationException {
        Context context = Context.enter();
        Scriptable scope = context.initStandardObjects();
        Method compiler = contextCompileFunction();
        compiler.setAccessible(true);

        Function factory = (Function) compiler.invoke(
                context,
                scope,
                "function(require, exports, module) { exports.answer = 42; }",
                null,
                null,
                "module.ts",
                1,
                null);
        NativeObject exports = new NativeObject(context);
        exports.setParentScope(scope);
        NativeObject module = new NativeObject(context);
        module.setParentScope(scope);

        factory.call(context, scope, exports, new Object[]{null, exports, module});

        Object answer = exports.get(context, "answer", exports);
        assertEquals(42D, ((Number) answer).doubleValue());
    }

    @Test
    void moduleRuntimeUsesDirectCompilerCapabilityWhenAvailable() {
        TypeScriptScriptDomain domain = new TypeScriptScriptDomain(
                "rhino-mixin-test",
                Path.of("rhino-mixin-test", "server_scripts"));
        Context context = new InvokerBackedContext();
        Scriptable scope = context.initStandardObjects();
        TypeScriptModuleRegistry.register(domain, "rhino-mixin-test:module.ts", "module.ts", "exports.answer = 42;");
        TypeScriptModuleRuntime runtime = new TypeScriptModuleRuntime(domain, context, scope);

        Object result = runtime.requireSelf("rhino-mixin-test:module.ts", scope);

        Scriptable exports = (Scriptable) result;
        assertEquals(42D, ((Number) exports.get(context, "answer", exports)).doubleValue());
    }

    private static Method contextCompileFunction() throws NoSuchMethodException {
        return Context.class.getDeclaredMethod(
                "compileFunction",
                Scriptable.class,
                String.class,
                Evaluator.class,
                ErrorReporter.class,
                String.class,
                int.class,
                Object.class);
    }

    private static final class InvokerBackedContext extends Context implements RhinoModuleFactoryCompiler {
        @Override
        public Function tsmodify$compileFunction(
                Scriptable scope,
                String source,
                Evaluator compiler,
                ErrorReporter compilationErrorReporter,
                String sourceName,
                int lineNumber,
                Object securityDomain) {
            try {
                Method method = contextCompileFunction();
                method.setAccessible(true);
                return (Function) method.invoke(
                        this,
                        scope,
                        source,
                        compiler,
                        compilationErrorReporter,
                        sourceName,
                        lineNumber,
                        securityDomain);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("Pinned Rhino Context.compileFunction invocation failed", e);
            }
        }
    }
}
