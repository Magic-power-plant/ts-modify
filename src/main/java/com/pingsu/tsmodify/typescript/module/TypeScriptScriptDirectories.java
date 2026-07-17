package com.pingsu.tsmodify.typescript.module;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class TypeScriptScriptDirectories {
    public static final String SHARED_SCRIPTS = "shared_scripts";

    private TypeScriptScriptDirectories() {
    }

    public static Path sharedScriptsRoot(Path kubeJsRoot) {
        return kubeJsRoot.resolve(SHARED_SCRIPTS);
    }

    public static Path sharedScriptsRootForDomain(Path scriptRoot) {
        Path parent = scriptRoot.getParent();
        return sharedScriptsRoot(parent == null ? scriptRoot : parent);
    }

    public static Path ensureSharedScriptsRoot(Path kubeJsRoot) throws IOException {
        Path sharedRoot = sharedScriptsRoot(kubeJsRoot);
        Files.createDirectories(sharedRoot);
        return sharedRoot;
    }
}
