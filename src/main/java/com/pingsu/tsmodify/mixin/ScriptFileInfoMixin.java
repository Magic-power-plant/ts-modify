package com.pingsu.tsmodify.mixin;

import com.pingsu.tsmodify.typescript.TypeScriptTranspiler;
import dev.latvian.mods.kubejs.script.ScriptFileInfo;
import dev.latvian.mods.kubejs.script.ScriptSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.io.IOException;
import java.util.List;

@Mixin(value = ScriptFileInfo.class, remap = false)
public abstract class ScriptFileInfoMixin {
    @Shadow
    @Final
    public String file;

    @Redirect(
            method = "preload",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/latvian/mods/kubejs/script/ScriptSource;readSource(Ldev/latvian/mods/kubejs/script/ScriptFileInfo;)Ljava/util/List;",
                    remap = false
            ),
            remap = false
    )
    private List<String> tsmodify$transpileTypeScript(ScriptSource source, ScriptFileInfo info) throws IOException {
        return TypeScriptTranspiler.transpile(file, source.readSource(info));
    }
}
