package com.pingsu.tsmodify.mixin;

import com.pingsu.tsmodify.kubejs.fluid.RegisteredFluidTypeHolder;
import net.minecraftforge.fluids.FluidType;
import net.minecraftforge.fluids.ForgeFlowingFluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Objects;

@Mixin(value = ForgeFlowingFluid.class, remap = false)
public abstract class ForgeFlowingFluidMixin implements RegisteredFluidTypeHolder {
    @Unique
    private FluidType tsmodify$registeredFluidType;

    @Override
    public FluidType tsmodify$getRegisteredFluidType() {
        return tsmodify$registeredFluidType;
    }

    @Override
    public void tsmodify$setRegisteredFluidType(FluidType fluidType) {
        tsmodify$registeredFluidType = Objects.requireNonNull(fluidType);
    }

    @Inject(
            method = "getFluidType()Lnet/minecraftforge/fluids/FluidType;",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private void tsmodify$useRegisteredFluidType(CallbackInfoReturnable<FluidType> cir) {
        if (tsmodify$registeredFluidType != null) {
            cir.setReturnValue(tsmodify$registeredFluidType);
        }
    }
}
