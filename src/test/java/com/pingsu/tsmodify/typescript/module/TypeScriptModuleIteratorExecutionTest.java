package com.pingsu.tsmodify.typescript.module;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.pingsu.tsmodify.typescript.TypeScriptTranspiler;
import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.Function;
import dev.latvian.mods.rhino.Scriptable;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Executes the loop shapes that SWC's ES5 lowering turns into iterator helper code.
 *
 * <p>The lowering declares {@code var _iterator} inside the loop's {@code try} block and reads it
 * from the {@code finally} block. That is valid JavaScript, but it only works if the module body is
 * executed in a scope where function-level {@code var} hoisting applies, so these cases are checked
 * through the real module runtime rather than by comparing transpiled text.</p>
 */
class TypeScriptModuleIteratorExecutionTest {
    @TempDir
    Path tempDir;

    @AfterEach
    void clearRegistry() {
        TypeScriptModuleRegistry.clear();
    }

    @Test
    void iteratesDestructuredEntriesOfAnImportedArray() {
        TypeScriptScriptDomain domain = domain("server");
        register(domain, "test:helpers.ts", """
                export const LABELS = ["alpha", "beta", "gamma"] as const
                """);
        register(domain, "test:consumer.ts", """
                import { LABELS } from "./helpers"

                export function entriesTotal(): number {
                  let total = 0
                  for (const [index, label] of LABELS.entries()) {
                    total += index + label.length
                  }
                  return total
                }
                """);

        assertEquals(17, exportedNumber(domain, "test:consumer.ts", "entriesTotal"));
    }

    @Test
    void iteratesAPlainImportedArray() {
        TypeScriptScriptDomain domain = domain("server");
        register(domain, "test:helpers.ts", """
                export const LABELS = ["alpha", "beta", "gamma"] as const
                """);
        register(domain, "test:consumer.ts", """
                import { LABELS } from "./helpers"

                export function counted(): number {
                  let count = 0
                  for (const label of LABELS) {
                    count += label.length
                  }
                  return count
                }
                """);

        assertEquals(14, exportedNumber(domain, "test:consumer.ts", "counted"));
    }

    @Test
    void breaksOutOfAnIteratorLoopEarly() {
        TypeScriptScriptDomain domain = domain("server");
        register(domain, "test:helpers.ts", """
                export const LABELS = ["alpha", "beta", "gamma"] as const
                """);
        register(domain, "test:consumer.ts", """
                import { LABELS } from "./helpers"

                export function firstTwo(): string {
                  let text = ""
                  for (const [index, label] of LABELS.entries()) {
                    if (index >= 2) {
                      break
                    }
                    text += label
                  }
                  return text
                }
                """);

        assertEquals("alphabeta", exportedString(domain, "test:consumer.ts", "firstTwo"));
    }

    private int exportedNumber(TypeScriptScriptDomain domain, String moduleId, String exportName) {
        return ((Number) callExport(domain, moduleId, exportName)).intValue();
    }

    private String exportedString(TypeScriptScriptDomain domain, String moduleId, String exportName) {
        return String.valueOf(callExport(domain, moduleId, exportName));
    }

    private Object callExport(TypeScriptScriptDomain domain, String moduleId, String exportName) {
        Context context = Context.enter();
        Scriptable scope = context.initStandardObjects();
        TypeScriptModuleRuntime runtime = new TypeScriptModuleRuntime(domain, context, scope);
        Scriptable exports = (Scriptable) runtime.requireSelf(moduleId, scope);
        Function function = (Function) exports.get(context, exportName, exports);
        return function.call(context, scope, exports, new Object[0]);
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
