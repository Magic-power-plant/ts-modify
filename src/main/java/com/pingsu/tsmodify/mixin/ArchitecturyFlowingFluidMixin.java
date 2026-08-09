package com.pingsu.tsmodify.mixin;

import com.pingsu.tsmodify.kubejs.fluid.RegisteredFluidTypeHolder;
import dev.architectury.core.fluid.ArchitecturyFlowingFluid;
import net.minecraftforge.fluids.FluidType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ArchitecturyFlowingFluid.class, remap = false)
public abstract class ArchitecturyFlowingFluidMixin {
    // Architectury 9.1 overrides this method; 9.2 inherits ForgeFlowingFluid's implementation instead.
    @Inject(
            method = "getFluidType()Lnet/minecraftforge/fluids/FluidType;",
            at = @At("HEAD"),
            cancellable = true,
            remap = false,
            require = 0,
            expect = 0
    )
    private void tsmodify$useRegisteredFluidType(CallbackInfoReturnable<FluidType> cir) {
        FluidType registeredFluidType = ((RegisteredFluidTypeHolder) this)
                .tsmodify$getRegisteredFluidType();
        if (registeredFluidType != null) {
            cir.setReturnValue(registeredFluidType);
        }
    }
}
