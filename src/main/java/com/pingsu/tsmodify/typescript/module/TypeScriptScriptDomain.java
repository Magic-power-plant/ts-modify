package com.pingsu.tsmodify.typescript.module;

import dev.latvian.mods.kubejs.script.ScriptType;

import java.nio.file.Path;

public record TypeScriptScriptDomain(String name, Path root) {
    public static TypeScriptScriptDomain from(ScriptType scriptType) {
        return new TypeScriptScriptDomain(scriptType.name, scriptType.path);
    }

    public Path sharedRoot() {
        return TypeScriptScriptDirectories.sharedScriptsRootForDomain(root);
    }
}
