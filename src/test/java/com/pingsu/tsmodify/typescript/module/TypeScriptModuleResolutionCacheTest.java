package com.pingsu.tsmodify.typescript.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.pingsu.tsmodify.typescript.TypeScriptTranspileException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TypeScriptModuleResolutionCacheTest {
    @TempDir
    Path tempDir;

    @AfterEach
    void clearRegistry() {
        TypeScriptModuleRegistry.clear();
    }

    @Test
    void cachesResolutionForTheWholeScriptLoad() throws IOException {
        TypeScriptScriptDomain domain = domain("server");
        write(domain.root(), "machine/crafter.ts");
        String requester = "test:machine/consumer.ts";

        assertEquals("test:machine/crafter.ts", TypeScriptModuleRegistry.resolve(domain, requester, "./crafter"));
        assertEquals("test:machine/crafter.ts", TypeScriptModuleRegistry.resolve(domain, requester, "./crafter.ts"));

        // Rename the file so that only a fresh probe could answer differently. The cached answer is
        // still returned, which is what proves the cache is actually consulted.
        Files.delete(domain.root().resolve("machine").resolve("crafter.ts"));
        write(domain.root(), "machine/crafter.tsx");
        assertEquals("test:machine/crafter.ts", TypeScriptModuleRegistry.resolve(domain, requester, "./crafter"));

        TypeScriptModuleRegistry.clear();

        assertEquals("test:machine/crafter.tsx", TypeScriptModuleRegistry.resolve(domain, requester, "./crafter"));
    }

    @Test
    void doesNotCacheFailedResolutions() throws IOException {
        TypeScriptScriptDomain domain = domain("server");
        String requester = "test:consumer.ts";

        assertThrows(TypeScriptTranspileException.class,
                () -> TypeScriptModuleRegistry.resolve(domain, requester, "./later"));

        write(domain.root(), "later.ts");

        assertEquals("test:later.ts", TypeScriptModuleRegistry.resolve(domain, requester, "./later"));
    }

    @Test
    void cachesSharedScriptResolutionAcrossRequesters() throws IOException {
        TypeScriptScriptDomain domain = domain("server");
        write(tempDir.resolve("kubejs").resolve("shared_scripts"), "items.ts");

        // Both requesters sit at the domain root, so "../shared_scripts" reaches kubejs/shared_scripts
        // and the second call must be answered from the cache rather than a second disk probe.
        assertEquals("shared:items.ts",
                TypeScriptModuleRegistry.resolve(domain, "test:first.ts", "../shared_scripts/items"));
        assertEquals("shared:items.ts",
                TypeScriptModuleRegistry.resolve(domain, "test:second.ts", "../shared_scripts/items"));
        assertEquals("shared:items.ts",
                TypeScriptModuleRegistry.resolve(domain, "test:first.ts", "shared:items"));
    }

    private TypeScriptScriptDomain domain(String name) {
        return new TypeScriptScriptDomain(name, tempDir.resolve("kubejs").resolve(name + "_scripts"));
    }

    private static void write(Path root, String relativePath) throws IOException {
        Path file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "export const value = 1;\n", StandardCharsets.UTF_8);
    }
}
