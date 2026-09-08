package com.pingsu.tsmodify.kubejs;

import com.pingsu.tsmodify.kubejs.fluid.FluidIngredientJS;
import com.pingsu.tsmodify.kubejs.mixin.KubeJsMixinApi;
import com.pingsu.tsmodify.kubejs.mixin.KubeJsMixinRuleRegistry;
import com.pingsu.tsmodify.typescript.module.TypeScriptScriptDirectories;
import com.pingsu.tsmodify.typescript.module.TypeScriptModuleRegistry;
import com.pingsu.tsmodify.typescript.module.TypeScriptModuleRuntime;
import dev.latvian.mods.kubejs.KubeJS;
import dev.latvian.mods.kubejs.KubeJSPlugin;
import dev.latvian.mods.kubejs.KubeJSPaths;
import dev.latvian.mods.kubejs.script.BindingsEvent;

import java.io.IOException;

public class TsmodifyKubeJSPlugin extends KubeJSPlugin {
    @Override
    public void init() {
        try {
            TypeScriptScriptDirectories.ensureSharedScriptsRoot(KubeJSPaths.DIRECTORY);
        } catch (IOException e) {
            KubeJS.LOGGER.error("Failed to create TS Modify shared script directory", e);
        }
    }

    @Override
    public void registerBindings(BindingsEvent event) {
        event.add("FluidIngredient", FluidIngredientJS.class);
        event.add("__tsmodify_modules", new TypeScriptModuleRuntime(event.manager));
        event.add("KubeJSMixin", new KubeJsMixinApi(event.manager));
    }

    @Override
    public void clearCaches() {
        TypeScriptModuleRegistry.clear();
        KubeJsMixinRuleRegistry.clear();
    }
}
