package com.pingsu.tsmodify.typescript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pingsu.transpiler.JavaScriptTarget;
import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.Scriptable;
import java.util.List;
import org.junit.jupiter.api.Test;

class TypeScriptTranspilerTest {
    @Test
    void kubejsModeTranspilesTypeScriptAndKeepsRuntimeScriptCompatible() {
        String source = """
                import { helper } from "./helper";
                export {};

                interface RecipeEvent {
                  server: any;
                }

                type ModeName = "add" | "remove";

                enum Mode {
                  Add = "add",
                  Remove = "remove",
                }

                const fileLocal: ModeName = "add" satisfies ModeName;
                let counter = 0;

                function addRecipe(event: RecipeEvent): void {
                  const message = `${Mode.Add}:${fileLocal}:${counter}`;
                  event.server?.tell?.(message);
                }

                function withDefault(anchor: string, enabled = true): string {
                  return anchor + enabled;
                }

                ServerEvents.recipes(event => addRecipe(event));
                """;

        TypeScriptSourceTranspileResult result = TypeScriptTranspiler.transpileSource(
                "server_scripts/example.ts",
                source,
                TypeScriptTranspileConfig.KUBEJS);

        String code = result.code();
        assertFalse(result.legacyFallback());
        assertNull(result.sourceMap());
        assertFalse(code.contains("interface RecipeEvent"));
        assertFalse(code.contains("type ModeName"));
        assertFalse(code.contains("import { helper }"));
        assertFalse(code.contains("export {};"));
        assertFalse(code.contains("satisfies"));
        assertFalse(code.contains(": RecipeEvent"));
        assertFalse(code.contains("=>"), code);
        assertFalse(code.contains("enabled = true"), code);
        assertFalse(code.contains("?."));
        assertFalse(code.contains("const fileLocal"));
        assertTrue(code.contains("var __tsmodify_"), code);
        assertTrue(code.contains("ServerEvents.recipes"));
        assertTrue(code.contains("__tsmodify_"));
        assertTrue(code.contains("addRecipe"));
        assertEquals(JavaScriptTarget.ES5, TypeScriptTranspileConfig.KUBEJS.transformTarget());
    }

    @Test
    void kubejsModeGuardsParenthesizedTypeAssertionStatementsAfterChains() {
        String source = """
                export {};

                function transfer(event: Internal.TransferProxyRecipeEvent): void {
                  let recipe = event.recipeType.recipeBuilder().id(event.proxyRecipeId + "_mbd2");

                  recipe.inputItems(event.proxyRecipe)
                    .outputItems(event.proxyRecipe)

                  (event as {mbdRecipe: Internal.MBDRecipe}).mbdRecipe = recipe.buildMBDRecipe();
                }
                """;

        TypeScriptSourceTranspileResult result = TypeScriptTranspiler.transpileSource(
                "server_scripts/machine/recipeTransfer.ts",
                source,
                TypeScriptTranspileConfig.KUBEJS.toBuilder().isolateTopLevelNames(false).build());

        String code = result.code();
        Context context = Context.enter();
        Scriptable scope = context.initStandardObjects();
        Object value = context.evaluateString(scope, code + """
                var calls = 0;
                var builder = {
                  id: function() { return this; },
                  inputItems: function() { calls++; return this; },
                  outputItems: function() { calls++; return this; },
                  buildMBDRecipe: function() { return 17; }
                };
                var event = { recipeType: { recipeBuilder: function() { return builder; } } };
                transfer(event);
                event.mbdRecipe + ":" + calls;
                """, "asi.ts", 1, null);
        assertEquals("17:2", value);
    }

    @Test
    void kubejsModuleBodyConvertsStaticImportsAndExportsToRuntimeModuleCalls() {
        String source = """
                import label, { sharedItems } from "./global";

                const localItems = sharedItems;
                export const value = label(localItems[0]);
                """;

        TypeScriptSourceTranspileResult result = TypeScriptTranspiler.transpileKubeJsModuleBody(
                "client_scripts/tooltips.ts",
                source);

        String code = result.code();
        assertFalse(code.contains("import {"));
        assertFalse(code.contains("export const"));
        assertTrue(code.contains("require(\"./global\")"));
        assertTrue(code.contains("exports.value ="));
        assertTrue(code.contains("sharedItems"));
    }

    @Test
    void kubejsModuleBodyHandlesAdjacentNamedExportsAndAliasesAtEndOfFile() {
        String source = "export function foo(value: number) { return value + 1; }\n"
                + "const g = 2;\n"
                + "export { g as answer };";

        String code = TypeScriptTranspiler.transpileKubeJsModuleBody("server_scripts/exports.ts", source).code();

        assertTrue(code.contains("exports.foo = foo;"), code);
        assertTrue(code.contains("exports.answer ="), code);
        assertFalse(code.contains("export "), code);
        assertFalse(code.contains("\n= foo;"), code);
    }

    @Test
    void kubejsModuleBodyHandlesReExportsAndEmptyExports() {
        String source = "export {}\nexport { value as renamed } from \"./dependency\"\nexport * from \"./other\"\n";

        String code = TypeScriptTranspiler.transpileKubeJsModuleBody("server_scripts/barrel.ts", source).code();

        assertFalse(code.contains("export "), code);
        assertTrue(code.contains("require(\"./dependency\")"), code);
        assertTrue(code.contains("exports.renamed"), code);
        assertTrue(code.contains("require(\"./other\")"), code);
    }

    @Test
    void kubejsModuleBodyAcceptsUnicodeImportAndExportBindings() {
        String source = """
                import { 值 as v } from "./dependency";
                const 本地 = v;
                const a = v;
                export { 本地 };
                export { a as 值 };
                """;

        String code = TypeScriptTranspiler.transpileKubeJsModuleBody(
                "server_scripts/unicode-bindings.ts",
                source).code();

        assertTrue(code.contains("var v = __tsmodify_module_0.值;"), code);
        assertTrue(code.contains("exports.本地 ="), code);
        assertTrue(code.contains("exports.值 ="), code);
        assertFalse(code.contains("import "), code);
        assertFalse(code.contains("export "), code);
    }

    @Test
    void kubejsModeLowersCommonEs2015SyntaxForRhino() {
        String source = """
                const values = [1, 2];
                const copy = [...values];
                var isArray = values instanceof Array;
                const record = { values };
                const { values: first } = record;
                function describe(value = `value:${first}`, ...rest) {
                  return `${value}:${rest.length}`;
                }
                const add = (left: number, right: number) => left + right;
                """;

        String code = TypeScriptTranspiler.transpileSource(
                "server_scripts/es2015.ts",
                source,
                TypeScriptTranspileConfig.KUBEJS.toBuilder()
                        .isolateTopLevelNames(false)
                        .build()).code();

        assertFalse(code.contains("const "), code);
        assertFalse(code.contains("let "), code);
        assertFalse(code.contains("=>"), code);
        assertFalse(code.contains("..."), code);
        assertFalse(code.contains("`"), code);
        assertFalse(code.contains("function describe(value ="), code);
        assertTrue(code.contains("arguments"), code);
        Context context = Context.enter();
        Scriptable scope = context.initStandardObjects();
        Object isArray = context.evaluateString(scope, code + "\n isArray;", "es2015.ts", 1, null);
        assertEquals(Boolean.TRUE, isArray);
    }

    @Test
    void kubejsModeLowersObjectRestAndSpreadBeforeEs2015ForRhino() {
        String source = """
                const source = { x: 1, y: 2, z: 3 };
                const { x, ...rest } = source;
                const merged = { ...rest, z: 4 };
                var result = x + ":" + rest.y + ":" + merged.z;
                """;

        String code = TypeScriptTranspiler.transpileSource(
                "server_scripts/object-rest-spread.ts",
                source,
                TypeScriptTranspileConfig.KUBEJS.toBuilder()
                        .isolateTopLevelNames(false)
                        .build()).code();

        assertFalse(code.contains("..."), code);
        Context context = Context.enter();
        Scriptable scope = context.initStandardObjects();
        Object result = context.evaluateString(
                scope,
                code + "\nresult;",
                "object-rest-spread.ts",
                1,
                null);
        assertEquals("1:2:4", result);
    }

    @Test
    void kubejsModeSplitsForVarDeclarationsInsideSingleStatementBodies() {
        String source = """
                var result = "";
                var initializerCalls = 0;
                if (false)
                  for (var skipped = ++initializerCalls, alsoSkipped = ++initializerCalls; false;) {
                  }
                if (true)
                  for (var a = 0, b = 3; a < 3; a++) {
                    b += a;
                  }
                result = b + ":" + initializerCalls;
                """;

        String code = TypeScriptTranspiler.transpileSource(
                "server_scripts/nested-for-var.ts",
                source,
                TypeScriptTranspileConfig.KUBEJS.toBuilder()
                        .isolateTopLevelNames(false)
                        .build()).code();

        assertFalse(code.contains("for(var a = 0, b = 3"), code);
        Context context = Context.enter();
        Scriptable scope = context.initStandardObjects();
        Object result = context.evaluateString(
                scope,
                code + "\nresult;",
                "nested-for-var.ts",
                1,
                null);
        assertEquals("6:0", result);
    }

    @Test
    void projectModePreservesModulesAndRewritesTypeScriptSpecifiers() {
        String source = """
                import { helper } from "./helper.ts";
                import "./startup.ts?phase=early";

                export async function run(value: string): Promise<string> {
                  const loaded = await import("./lazy.ts#chunk");
                  return helper(value) + loaded.name;
                }

                const required = require("./legacy.cts");
                console.log(required);
                """;

        TypeScriptSourceTranspileResult result = TypeScriptTranspiler.transpileSource(
                "src/index.ts",
                source,
                TypeScriptTranspileConfig.PROJECT);

        String code = result.code();
        assertFalse(result.legacyFallback());
        assertTrue(code.contains("from \"./helper.js\""));
        assertTrue(code.contains("import \"./startup.js?phase=early\""));
        assertTrue(code.contains("import(\"./lazy.js#chunk\")"));
        assertTrue(code.contains("require(\"./legacy.cjs\")"));
        assertFalse(code.contains("__tsmodify_"));
        assertTrue(code.contains("export"));
    }

    @Test
    void reportsInvalidSourceWithTranspilationContext() {
        TypeScriptTranspileException failure = assertThrows(
                TypeScriptTranspileException.class,
                () -> TypeScriptTranspiler.transpileSource(
                        "server_scripts/broken.ts",
                        "const broken: string = ;",
                        TypeScriptTranspileConfig.KUBEJS));

        assertEquals("Translanguage transpilation", failure.context().phase());
        assertTrue(failure.getMessage().contains("server_scripts/broken.ts"));
        assertTrue(failure.getMessage().contains("TS Modify error during Translanguage transpilation"));
    }

    @Test
    void ignoresDeclarationsAndNonTypeScriptFiles() {
        List<String> declaration = List.of("declare const global: any;");
        List<String> javascript = List.of("const value = 1;");

        assertFalse(TypeScriptTranspiler.isTranspilableTypeScriptFile("globals.d.ts"));
        assertSame(declaration, TypeScriptTranspiler.transpile("globals.d.ts", declaration));
        assertSame(javascript, TypeScriptTranspiler.transpile("script.js", javascript));
    }

    @Test
    void mapsTypeScriptOutputNames() {
        assertEquals("script.js", TypeScriptTranspiler.outputFileName("script.ts"));
        assertEquals("component.js", TypeScriptTranspiler.outputFileName("component.tsx"));
        assertEquals("module.mjs", TypeScriptTranspiler.outputFileName("module.mts"));
        assertEquals("common.cjs", TypeScriptTranspiler.outputFileName("common.cts"));
        assertEquals("plain.js", TypeScriptTranspiler.outputFileName("plain.js"));
    }
}
