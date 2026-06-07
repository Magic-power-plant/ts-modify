package com.pingsu.tsmodify.kubejs;

import com.pingsu.tsmodify.kubejs.fluid.FluidIngredientJS;
import dev.latvian.mods.kubejs.KubeJSPlugin;
import dev.latvian.mods.kubejs.script.BindingsEvent;

public class TsmodifyKubeJSPlugin extends KubeJSPlugin {
    @Override
    public void registerBindings(BindingsEvent event) {
        event.add("FluidIngredient", FluidIngredientJS.class);
    }
}
