package com.pingsu.tsmodify.mixin;

import com.pingsu.tsmodify.typescript.rhino.RhinoScriptCompilationCache;
import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.Script;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Extends compiled-script reuse to ordinary Rhino {@link Context#evaluateString} callers.
 */
@Mixin(value = Context.class, remap = false)
public abstract class ContextEvaluateStringMixin {
    @Redirect(
            method = "evaluateString",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/latvian/mods/rhino/Context;compileString(Ljava/lang/String;Ljava/lang/String;ILjava/lang/Object;)Ldev/latvian/mods/rhino/Script;",
                    remap = false
            ),
            remap = false
    )
    private Script tsmodify$compileCachedScript(
            Context context,
            String source,
            String sourceName,
            int lineNumber,
            Object securityDomain) {
        return RhinoScriptCompilationCache.compile(context, source, sourceName, lineNumber, securityDomain);
    }
}
