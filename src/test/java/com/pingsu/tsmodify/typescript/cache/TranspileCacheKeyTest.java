package com.pingsu.tsmodify.typescript.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.pingsu.tsmodify.typescript.TypeScriptTranspiler;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class TranspileCacheKeyTest {
    private static final String FINGERPRINT = TranspilePipelineFingerprint.value();
    private static final byte[] SOURCE = "export const answer: number = 42;\n"
            .getBytes(StandardCharsets.UTF_8);

    @Test
    void identifiesOnePhysicalFileThroughEverySourceNameShape() {
        TranspileCacheKey preload = TranspileCacheKey.of("machine/crafter.ts", SOURCE, FINGERPRINT);
        TranspileCacheKey diskLoad = TranspileCacheKey.of("crafter.ts", SOURCE, FINGERPRINT);
        TranspileCacheKey sharedDiskLoad = TranspileCacheKey.of("shared_scripts/crafter.ts", SOURCE, FINGERPRINT);

        assertEquals(preload, diskLoad);
        assertEquals(preload.id(), sharedDiskLoad.id());
    }

    @Test
    void separatesMediaTypesAndContent() {
        TranspileCacheKey typescript = TranspileCacheKey.of("crafter.ts", SOURCE, FINGERPRINT);

        assertNotEquals(typescript.id(), TranspileCacheKey.of("crafter.tsx", SOURCE, FINGERPRINT).id());
        assertNotEquals(typescript.id(),
                TranspileCacheKey.of("crafter.ts", "export const answer = 42;\n"
                        .getBytes(StandardCharsets.UTF_8), FINGERPRINT).id());
        assertNotEquals(typescript.id(), TranspileCacheKey.of("crafter.ts", SOURCE, "other").id());
        assertEquals(".ts", typescript.extension());
    }

    @Test
    void shardsAndNamesTheEntryFromTheKey() {
        TranspileCacheKey key = TranspileCacheKey.of("crafter.ts", SOURCE, FINGERPRINT);

        assertEquals(64, key.id().length());
        assertEquals(key.id().substring(0, 2), key.shard());
        assertEquals(key.id() + ".js", key.fileName());
    }

    /**
     * The key deliberately ignores the source directory. That is only sound while the emitted body
     * is a pure function of extension plus content, which is what this pins.
     */
    @Test
    void emitsTheSameBodyForOneExtensionRegardlessOfSourceName() {
        String source = "export const answer: number = 42;\n";

        String serverSide = TypeScriptTranspiler.transpileKubeJsModuleBody("machine/crafter.ts", source).code();
        String sharedSide = TypeScriptTranspiler.transpileKubeJsModuleBody("crafter.ts", source).code();

        assertEquals(serverSide, sharedSide);
    }
}
