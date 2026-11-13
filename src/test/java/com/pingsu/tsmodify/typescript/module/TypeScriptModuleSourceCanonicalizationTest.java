package com.pingsu.tsmodify.typescript.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.pingsu.tsmodify.typescript.cache.TranspileCacheKey;
import com.pingsu.tsmodify.typescript.cache.TranspilePipelineFingerprint;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Pins that one physical script reaches the disk cache through exactly one key.
 *
 * <p>KubeJS preloads {@code String.join("\n", lines)}, which drops the file's trailing line
 * terminator, while the module loader reads the file text and keeps it. Both shapes are hashed for
 * the cache key, so without canonicalization one file occupies two entries: it is transpiled twice,
 * and whichever shape misses second re-pays the one-time WASM module load.</p>
 */
class TypeScriptModuleSourceCanonicalizationTest {
    private static final String FILE_TEXT = "export const answer: number = 42;\n";

    @Test
    void preloadedLinesAndRawFileTextMapToOneCacheKey() {
        String preloaded = String.join("\n", List.of("export const answer: number = 42;"));

        assertEquals("export const answer: number = 42;", preloaded);
        assertEquals(TypeScriptModuleRegistry.canonicalSourceText(preloaded),
                TypeScriptModuleRegistry.canonicalSourceText(FILE_TEXT));
        assertEquals(keyId(preloaded), keyId(FILE_TEXT));
    }

    @Test
    void collapsesEveryLineEndingAndTrailingTerminator() {
        String canonical = TypeScriptModuleRegistry.canonicalSourceText("a\r\nb\rc\n\n");

        assertEquals("a\nb\nc", canonical);
        assertEquals(canonical, TypeScriptModuleRegistry.canonicalSourceText(canonical));
    }

    @Test
    void keepsDistinctContentDistinct() {
        assertNotEquals(keyId("export const answer = 1;"), keyId("export const answer = 2;"));
    }

    private static String keyId(String source) {
        String canonical = TypeScriptModuleRegistry.canonicalSourceText(source);
        return TranspileCacheKey.of("crafter.ts", canonical.getBytes(StandardCharsets.UTF_8),
                TranspilePipelineFingerprint.value()).id();
    }
}
