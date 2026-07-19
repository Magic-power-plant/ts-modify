package com.pingsu.tsmodify.typescript.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TypeScriptScriptDirectoriesTest {
    @TempDir
    Path tempDir;

    @Test
    void createsSharedScriptsRootUnderKubeJsDirectory() throws IOException {
        Path kubeJsRoot = tempDir.resolve("instance").resolve("kubejs");

        Path sharedRoot = TypeScriptScriptDirectories.ensureSharedScriptsRoot(kubeJsRoot);

        assertEquals(kubeJsRoot.resolve("shared_scripts"), sharedRoot);
        assertTrue(Files.isDirectory(sharedRoot));
    }
}
