package com.pingsu.tsmodify.kubejs;

import com.pingsu.tsmodify.kubejs.fluid.FluidIngredientJS;
import com.pingsu.tsmodify.kubejs.mixin.KubeJsMixinApi;
import com.pingsu.tsmodify.kubejs.mixin.KubeJsMixinRuleRegistry;
import com.pingsu.tsmodify.kubejs.primitive.JavaPrimitiveTypes;
import com.pingsu.tsmodify.typescript.cache.TranspileMetrics;
import com.pingsu.tsmodify.typescript.module.TypeScriptScriptDirectories;
import com.pingsu.tsmodify.typescript.module.TypeScriptModuleRegistry;
import com.pingsu.tsmodify.typescript.module.TypeScriptModuleRuntime;
import dev.latvian.mods.kubejs.KubeJS;
import dev.latvian.mods.kubejs.KubeJSPlugin;
import dev.latvian.mods.kubejs.KubeJSPaths;
import dev.latvian.mods.kubejs.script.BindingsEvent;
import dev.latvian.mods.kubejs.script.ScriptType;
import dev.latvian.mods.rhino.util.wrap.TypeWrappers;

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
        JavaPrimitiveTypes.registerBindings(event);
        event.add("__tsmodify_modules", new TypeScriptModuleRuntime(event.manager));
        event.add("KubeJSMixin", new KubeJsMixinApi(event.manager));
    }

    @Override
    public void registerTypeWrappers(ScriptType scriptType, TypeWrappers typeWrappers) {
        JavaPrimitiveTypes.registerTypeWrappers(typeWrappers);
    }

    @Override
    public void clearCaches() {
        TypeScriptModuleRegistry.clear();
        // KubeJS calls this on every script (re)load, which is the natural end of one transpile
        // session. The disk cache itself deliberately survives: it is keyed by source content and
        // pipeline identity, so reloads and restarts can reuse it.
        TranspileMetrics.flushSummary("kubejs cache clear");
        // Mixin rules are registered only during the first startup load. Their target classes can
        // be defined after later server or client script reloads, so they intentionally outlive
        // KubeJS's general cache lifecycle.
        KubeJsMixinRuleRegistry.onKubeJsCacheClear();
        // Defensive second flush (D4): the primary flush runs on FMLLoadCompleteEvent, which is
        // normally the last word. A reload that somehow precedes it still gets its pending targets
        // retransformed here instead of waiting for the next restart. The pending set is usually
        // empty, which makes this call free.
        KubeJsMixinRuleRegistry.flushPendingRetransforms();
    }
}
