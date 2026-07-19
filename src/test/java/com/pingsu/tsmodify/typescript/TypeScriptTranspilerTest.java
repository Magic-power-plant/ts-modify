package com.pingsu.tsmodify.typescript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.caoccao.javet.swc4j.enums.Swc4jEsVersion;
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
        assertTrue(code.contains("=>"));
        assertFalse(code.contains("?."));
        assertFalse(code.contains("const fileLocal"));
        assertTrue(code.contains("const __tsmodify_"));
        assertTrue(code.contains("== null ? undefined"));
        assertTrue(code.contains("ServerEvents.recipes"));
        assertTrue(code.contains("__tsmodify_"));
        assertTrue(code.contains("addRecipe"));
        assertEquals(Swc4jEsVersion.ES2015, TypeScriptTranspileConfig.KUBEJS.transformTarget());
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
                TypeScriptTranspileConfig.KUBEJS);

        String code = result.code();
        assertFalse(code.contains(".outputItems(event.proxyRecipe)(event).mbdRecipe"));
        assertTrue(code.contains(".outputItems(event.proxyRecipe);"));
        assertTrue(code.contains("event.mbdRecipe = recipe.buildMBDRecipe();"));
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
        assertTrue(code.contains("exports.value = value"));
        assertTrue(code.contains("sharedItems"));
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

        assertEquals("SWC transpilation", failure.context().phase());
        assertTrue(failure.getMessage().contains("server_scripts/broken.ts"));
        assertTrue(failure.getMessage().contains("TS Modify error during SWC transpilation"));
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
