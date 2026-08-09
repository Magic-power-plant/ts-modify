package com.pingsu.tsmodify.typescript.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pingsu.tsmodify.typescript.TypeScriptTranspiler;
import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.Function;
import dev.latvian.mods.rhino.Scriptable;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TypeScriptModuleRuntimeTest {
    @TempDir
    Path tempDir;

    @AfterEach
    void clearRegistry() {
        TypeScriptModuleRegistry.clear();
    }

    @Test
    void loadsNamedDefaultAndReExportedModulesThroughRuntimeRequire() {
        TypeScriptScriptDomain domain = domain("server");
        register(domain, "test:helper.ts", """
                export const items = ["minecraft:stone"];

                export default function label(value: string): string {
                  return value + ":ok";
                }
                """);
        register(domain, "test:barrel.ts", """
                export { items as things } from "./helper";
                """);
        register(domain, "test:consumer.ts", """
                import label from "./helper";
                import { things } from "./barrel";

                export const value = label(things[0]);
                """);

        Context context = Context.enter();
        Scriptable scope = context.initStandardObjects();
        TypeScriptModuleRuntime runtime = new TypeScriptModuleRuntime(domain, context, scope);

        Object exports = runtime.requireSelf("test:consumer.ts", scope);

        assertTrue(exports instanceof Scriptable);
        Scriptable scriptableExports = (Scriptable) exports;
        assertEquals("minecraft:stone:ok", String.valueOf(scriptableExports.get(context, "value", scriptableExports)));
        assertSame(exports, runtime.requireSelf("test:consumer.ts", scope));
    }

    @Test
    void resolvesSharedScriptsFromServerAndClientDomains() {
        TypeScriptScriptDomain server = domain("server");
        TypeScriptScriptDomain client = domain("client");
        register(server, "shared:constants.ts", """
                export const tooltip = "shared-tooltip";
                """);
        register(client, "shared:constants.ts", """
                export const tooltip = "shared-tooltip";
                """);
        register(server, "test:consumer.ts", """
                import { tooltip } from "../shared_scripts/constants";
                export const value = tooltip;
                """);
        register(client, "test:consumer.ts", """
                import { tooltip } from "../shared_scripts/constants";
                export const value = tooltip;
                """);

        assertEquals("shared-tooltip", exportedValue(server, "test:consumer.ts", "value"));
        assertEquals("shared-tooltip", exportedValue(client, "test:consumer.ts", "value"));
    }

    @Test
    void resolvesExtensionlessModulesAndSupportsRepeatedDynamicRequireCalls() {
        TypeScriptScriptDomain domain = domain("server");
        register(domain, "test:one.ts", "export const value = 1;");
        register(domain, "test:two.ts", "export const value = 2;");
        register(domain, "test:consumer.ts", """
                export function values(): number {
                  return require("./one").value + require("./two").value + require("./one").value;
                }
                """);

        assertEquals("test:one.ts", TypeScriptModuleRegistry.resolve(domain, "test:consumer.ts", "./one"));

        Context context = Context.enter();
        Scriptable scope = context.initStandardObjects();
        TypeScriptModuleRuntime runtime = new TypeScriptModuleRuntime(domain, context, scope);
        Scriptable exports = (Scriptable) runtime.requireSelf("test:consumer.ts", scope);
        Function values = (Function) exports.get(context, "values", exports);

        Object result = values.call(context, scope, exports, new Object[0]);
        assertEquals(4, ((Number) result).intValue());
    }

    private Object exportedValue(TypeScriptScriptDomain domain, String moduleId, String exportName) {
        Context context = Context.enter();
        Scriptable scope = context.initStandardObjects();
        TypeScriptModuleRuntime runtime = new TypeScriptModuleRuntime(domain, context, scope);
        Object exports = runtime.requireSelf(moduleId, scope);
        Scriptable scriptableExports = (Scriptable) exports;
        return scriptableExports.get(context, exportName, scriptableExports);
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
