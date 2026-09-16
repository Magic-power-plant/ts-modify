package com.pingsu.tsmodify.kubejs.mixin;

import com.pingsu.tsmodify.Tsmodify;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;

/**
 * Retransforms already-loaded KubeJS mixin targets at the first safe point after startup scripts ran.
 *
 * <p>{@code retransformClasses} is a VM operation that requires a safepoint, so it must not run
 * while the mixin registry lock is held or in the middle of ModLauncher's bootstrap. By the time
 * {@code FMLLoadCompleteEvent} fires, every mod has been constructed and the first startup script
 * load is finished.</p>
 *
 * <p>This listener is common code: it never references a client-only class, and it is harmless on a
 * dedicated server. Without the optional agent the pending set stays empty and the hook is a no-op.</p>
 */
@Mod.EventBusSubscriber(modid = Tsmodify.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class KubeJsMixinRetransformLifecycle {
    private KubeJsMixinRetransformLifecycle() {
    }

    @SubscribeEvent
    public static void onLoadComplete(FMLLoadCompleteEvent event) {
        KubeJsMixinRuleRegistry.flushPendingRetransforms();
    }
}
