package com.pingsu.tsmodify.mixin;

import com.pingsu.tsmodify.typescript.rhino.RhinoScriptCompilationCache;
import dev.latvian.mods.kubejs.script.ScriptFile;
import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.Scriptable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Reuses compiled Rhino bytecode when KubeJS reloads an unchanged script file.
 */
@Mixin(value = ScriptFile.class, remap = false)
public abstract class ScriptFileMixin {
    @Redirect(
            method = "load",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/latvian/mods/rhino/Context;evaluateString(Ldev/latvian/mods/rhino/Scriptable;Ljava/lang/String;Ljava/lang/String;ILjava/lang/Object;)Ljava/lang/Object;",
                    remap = false
            ),
            remap = false
    )
    private Object tsmodify$evaluateCachedScript(
            Context context,
            Scriptable scope,
            String source,
            String sourceName,
            int lineNumber,
            Object securityDomain) {
        return RhinoScriptCompilationCache.evaluate(context, scope, source, sourceName, lineNumber, securityDomain);
    }
}
