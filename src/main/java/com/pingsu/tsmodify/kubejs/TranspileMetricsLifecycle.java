package com.pingsu.tsmodify.kubejs;

import com.pingsu.tsmodify.Tsmodify;
import com.pingsu.tsmodify.typescript.cache.TranspileMetrics;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Reports the transpile metrics of one script load session while the game is still running.
 *
 * <p>KubeJS transpiles server scripts during server startup, long before any cache clear or JVM
 * exit, so a session that is only flushed from those two places stays invisible in a live instance
 * that is simply stopped. This listener flushes the server script session at the first safe point
 * after it finishes. The startup script and client script sessions are still covered by the KubeJS
 * cache clear hook and the shutdown summary.</p>
 *
 * <p>Common code: it never references a client-only class and is harmless on a dedicated server.</p>
 */
@Mod.EventBusSubscriber(modid = Tsmodify.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class TranspileMetricsLifecycle {
    private TranspileMetricsLifecycle() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        TranspileMetrics.flushSummary("server started");
    }
}
