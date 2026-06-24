package com.pingsu.tsmodify.typescript.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TypeScriptProjectTranspilerTest {
    @TempDir
    Path tempDir;

    @Test
    void transpilesWholeProjectAndCopiesNonTypeScriptFiles() throws IOException {
        Path sourceRoot = tempDir.resolve("src");
        Path outputRoot = tempDir.resolve("out");
        Files.createDirectories(sourceRoot.resolve("nested"));
        Files.writeString(sourceRoot.resolve("index.ts"), """
                import { helper } from "./nested/helper.ts";
                import "./side-effect.mts";

                export const value: string = helper("ok");
                """, StandardCharsets.UTF_8);
        Files.writeString(sourceRoot.resolve("side-effect.mts"), "export const side = true;", StandardCharsets.UTF_8);
        Files.writeString(sourceRoot.resolve("legacy.cts"), "export = { legacy: true };", StandardCharsets.UTF_8);
        Files.writeString(sourceRoot.resolve("nested").resolve("helper.ts"), """
                export function helper(value: string): string {
                  return value;
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(sourceRoot.resolve("globals.d.ts"), "declare const global: any;", StandardCharsets.UTF_8);
        Files.writeString(sourceRoot.resolve("data.json"), "{\"enabled\":true}", StandardCharsets.UTF_8);

        TypeScriptProjectTranspileResult result = TypeScriptProjectTranspiler.transpileProject(sourceRoot, outputRoot);

        assertTrue(result.successful());
        assertEquals(5, result.files().size());
        assertEquals(4, result.transpiledFileCount());
        assertTrue(Files.exists(outputRoot.resolve("index.js")));
        assertTrue(Files.exists(outputRoot.resolve("side-effect.mjs")));
        assertTrue(Files.exists(outputRoot.resolve("legacy.cjs")));
        assertTrue(Files.exists(outputRoot.resolve("nested").resolve("helper.js")));
        assertTrue(Files.exists(outputRoot.resolve("data.json")));
        assertFalse(Files.exists(outputRoot.resolve("globals.js")));
        assertFalse(Files.exists(outputRoot.resolve("globals.d.ts")));

        String index = Files.readString(outputRoot.resolve("index.js"), StandardCharsets.UTF_8);
        assertTrue(index.contains("from \"./nested/helper.js\""));
        assertTrue(index.contains("import \"./side-effect.mjs\""));
    }

    @Test
    void rejectsOutputRootInsideSourceRoot() throws IOException {
        Path sourceRoot = tempDir.resolve("src");
        Files.createDirectories(sourceRoot);

        IOException failure = assertThrows(
                IOException.class,
                () -> TypeScriptProjectTranspiler.transpileProject(sourceRoot, sourceRoot.resolve("out")));

        assertTrue(failure.getMessage().contains("must not be the source root"));
    }
}
