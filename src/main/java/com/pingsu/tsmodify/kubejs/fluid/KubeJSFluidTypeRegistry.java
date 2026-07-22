package com.pingsu.tsmodify.kubejs.fluid;

import com.pingsu.tsmodify.Tsmodify;
import dev.latvian.mods.kubejs.fluid.FluidBuilder;
import dev.latvian.mods.kubejs.registry.BuilderBase;
import dev.latvian.mods.kubejs.registry.RegistryInfo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fluids.FluidType;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.IForgeRegistry;
import net.minecraftforge.registries.RegisterEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Mod.EventBusSubscriber(modid = Tsmodify.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class KubeJSFluidTypeRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger("TS Modify");

    private KubeJSFluidTypeRegistry() {
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRegister(RegisterEvent event) {
        if (event.getRegistryKey().equals(ForgeRegistries.Keys.FLUIDS)) {
            // KubeJS only exposes the Fluid builders, so capture their type after both variants exist.
            bindSharedFluidTypes();
        } else if (event.getRegistryKey().equals(ForgeRegistries.Keys.FLUID_TYPES)) {
            registerFluidTypes(event);
        }
    }

    static List<FluidBuilder> findSourceBuilders(Iterable<? extends BuilderBase<?>> builders) {
        List<FluidBuilder> sourceBuilders = new ArrayList<>();
        for (BuilderBase<?> builder : builders) {
            if (isSourceBuilderType(builder.getClass(), builder.dummyBuilder)) {
                sourceBuilders.add((FluidBuilder) builder);
            }
        }
        return List.copyOf(sourceBuilders);
    }

    static boolean isSourceBuilderType(Class<?> builderType, boolean dummyBuilder) {
        return FluidBuilder.class.isAssignableFrom(builderType) && !dummyBuilder;
    }

    private static void bindSharedFluidTypes() {
        int bound = 0;
        for (FluidBuilder builder : findSourceBuilders(RegistryInfo.FLUID.objects.values())) {
            if (bindSharedFluidType(builder) != null) {
                bound++;
            }
        }

        if (bound > 0) {
            LOGGER.debug("Bound {} KubeJS source/flowing fluid pair(s) to shared Forge fluid types", bound);
        }
    }

    private static void registerFluidTypes(RegisterEvent event) {
        IForgeRegistry<FluidType> registry = event.getForgeRegistry();
        if (registry == null) {
            throw new IllegalStateException("Forge fluid type RegisterEvent did not expose a Forge registry");
        }

        int registered = 0;
        for (FluidBuilder builder : findSourceBuilders(RegistryInfo.FLUID.objects.values())) {
            FluidType fluidType = bindSharedFluidType(builder);
            if (fluidType == null) {
                continue;
            }

            FluidType existing = registry.getValue(builder.id);
            if (existing != null && existing != fluidType) {
                throw new IllegalStateException(
                        "Cannot register KubeJS fluid type '" + builder.id
                                + "': that Forge fluid type ID is already occupied");
            }

            if (existing == null) {
                event.register(ForgeRegistries.Keys.FLUID_TYPES, builder.id, () -> fluidType);
                registered++;
            }

            if (registry.getValue(builder.id) != fluidType || !builder.id.equals(registry.getKey(fluidType))) {
                throw new IllegalStateException(
                        "Forge did not retain KubeJS fluid type '" + builder.id + "' under its source fluid ID");
            }
        }

        if (registered > 0) {
            LOGGER.info("Registered {} KubeJS fluid type(s) in {}", registered, ForgeRegistries.Keys.FLUID_TYPES.location());
        }
    }

    private static FluidType bindSharedFluidType(FluidBuilder builder) {
        FlowingFluid source = builder.get();
        FlowingFluid flowing = builder.flowingFluid.get();
        if (source == null && flowing == null) {
            return null;
        }
        if (source == null || flowing == null) {
            throw new IllegalStateException(
                    "KubeJS fluid pair '" + builder.id + "' was only partially registered before Forge fluid types");
        }

        requireRegisteredFluid(builder.id, source);
        requireRegisteredFluid(builder.flowingFluid.id, flowing);

        FluidType fluidType = Objects.requireNonNull(
                source.getFluidType(),
                () -> "KubeJS source fluid '" + builder.id + "' returned a null Forge fluid type");
        requireHolder(builder.id, source).tsmodify$setRegisteredFluidType(fluidType);
        requireHolder(builder.flowingFluid.id, flowing).tsmodify$setRegisteredFluidType(fluidType);
        return fluidType;
    }

    private static void requireRegisteredFluid(ResourceLocation expectedId, FlowingFluid fluid) {
        ResourceLocation actualId = ForgeRegistries.FLUIDS.getKey(fluid);
        if (!expectedId.equals(actualId)) {
            throw new IllegalStateException(
                    "KubeJS fluid '" + expectedId + "' was not available in minecraft:fluid before Forge fluid types"
                            + " (actual ID: " + actualId + ")");
        }
    }

    private static RegisteredFluidTypeHolder requireHolder(ResourceLocation id, FlowingFluid fluid) {
        if (fluid instanceof RegisteredFluidTypeHolder holder) {
            return holder;
        }
        throw new IllegalStateException(
                "KubeJS fluid '" + id + "' does not expose TS Modify's registered fluid type hook; "
                        + "ArchitecturyFlowingFluidMixin was not applied");
    }
}
