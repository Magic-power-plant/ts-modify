package com.pingsu.tsmodify.kubejs.fluid;

import dev.latvian.mods.kubejs.fluid.FluidBuilder;
import dev.latvian.mods.kubejs.fluid.FlowingFluidBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KubeJSFluidTypeRegistryTest {
    @Test
    void identifiesOnlyNonDummySourceFluidBuilderTypes() {
        assertTrue(KubeJSFluidTypeRegistry.isSourceBuilderType(FluidBuilder.class, false));
        assertFalse(KubeJSFluidTypeRegistry.isSourceBuilderType(FluidBuilder.class, true));
        assertFalse(KubeJSFluidTypeRegistry.isSourceBuilderType(FlowingFluidBuilder.class, false));
    }
}
