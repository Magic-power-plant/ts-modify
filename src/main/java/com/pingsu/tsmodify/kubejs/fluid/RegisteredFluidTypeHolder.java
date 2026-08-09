package com.pingsu.tsmodify.kubejs.fluid;

import net.minecraftforge.fluids.FluidType;

public interface RegisteredFluidTypeHolder {
    FluidType tsmodify$getRegisteredFluidType();

    void tsmodify$setRegisteredFluidType(FluidType fluidType);
}
