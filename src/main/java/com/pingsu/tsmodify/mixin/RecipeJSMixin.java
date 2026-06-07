package com.pingsu.tsmodify.mixin;

import com.google.gson.JsonElement;
import com.pingsu.tsmodify.kubejs.fluid.FluidIngredientJS;
import dev.latvian.mods.kubejs.fluid.InputFluid;
import dev.latvian.mods.kubejs.recipe.RecipeJS;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = RecipeJS.class, remap = false)
public abstract class RecipeJSMixin {
    @Inject(method = "inputFluidHasPriority", at = @At("HEAD"), cancellable = true)
    private void tsmodify$inputFluidTagHasPriority(Object value, CallbackInfoReturnable<Boolean> cir) {
        if (FluidIngredientJS.isFluidIngredient(value)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "readInputFluid", at = @At("HEAD"), cancellable = true)
    private void tsmodify$readFluidIngredient(Object value, CallbackInfoReturnable<InputFluid> cir) {
        if (FluidIngredientJS.isFluidIngredient(value)) {
            cir.setReturnValue(FluidIngredientJS.of(value));
        }
    }

    @Inject(method = "writeInputFluid", at = @At("HEAD"), cancellable = true)
    private void tsmodify$writeFluidIngredient(InputFluid value, CallbackInfoReturnable<JsonElement> cir) {
        if (value instanceof FluidIngredientJS ingredient) {
            cir.setReturnValue(ingredient.toJson());
        }
    }
}
