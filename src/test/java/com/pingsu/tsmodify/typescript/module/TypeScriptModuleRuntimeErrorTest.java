package com.pingsu.tsmodify.typescript.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pingsu.tsmodify.typescript.TypeScriptTranspileException;
import com.pingsu.tsmodify.typescript.TypeScriptTranspiler;
import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.Scriptable;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TypeScriptModuleRuntimeErrorTest {
    @TempDir
    Path tempDir;

    @AfterEach
    void clearRegistry() {
        TypeScriptModuleRegistry.clear();
    }

    @Test
    void reportsModuleExecutionAndSourceContext() {
        TypeScriptScriptDomain domain = domain("server");
        register(domain, "test:broken.ts", "const line = 1;\nthrow new Error(\"boom\");");

        Context context = Context.enter();
        Scriptable scope = context.initStandardObjects();
        TypeScriptModuleRuntime runtime = new TypeScriptModuleRuntime(domain, context, scope);

        TypeScriptTranspileException failure = assertThrows(
                TypeScriptTranspileException.class,
                () -> runtime.requireSelf("test:broken.ts", scope));

        assertEquals("module execution", failure.context().phase());
        assertEquals("test:broken.ts", failure.context().moduleId());
        assertTrue(failure.getMessage().contains("source=broken.ts"));
        assertTrue(failure.getMessage().contains("module-chain=test:broken.ts"));
        assertTrue(failure.getMessage().contains("boom"));
    }

    @Test
    void reportsRequesterWhenModuleResolutionFails() {
        TypeScriptScriptDomain domain = domain("server");
        register(domain, "test:consumer.ts", "import \"./missing\";\nexport const value = 1;");

        Context context = Context.enter();
        Scriptable scope = context.initStandardObjects();
        TypeScriptModuleRuntime runtime = new TypeScriptModuleRuntime(domain, context, scope);

        TypeScriptTranspileException failure = assertThrows(
                TypeScriptTranspileException.class,
                () -> runtime.requireSelf("test:consumer.ts", scope));

        assertTrue(failure.getMessage().contains("module=./missing"));
        assertTrue(failure.getMessage().contains("requester=test:consumer.ts"));
        assertTrue(failure.getCause().getMessage().contains("module resolution"));
    }

    private static void register(TypeScriptScriptDomain domain, String moduleId, String source) {
        String path = moduleId.substring(moduleId.indexOf(':') + 1);
        String body = TypeScriptTranspiler.transpileKubeJsModuleBody(path, source).code();
        TypeScriptModuleRegistry.register(domain, moduleId, path, body);
    }

    private TypeScriptScriptDomain domain(String name) {
        return new TypeScriptScriptDomain(name, tempDir.resolve("kubejs").resolve(name + "_scripts"));
    }
}
