package com.pingsu.tsmodify.mixin;

import com.pingsu.tsmodify.typescript.TypeScriptErrorReporter;
import dev.latvian.mods.kubejs.event.EventExit;
import dev.latvian.mods.kubejs.event.EventHandlerContainer;
import dev.latvian.mods.kubejs.event.EventResult;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Preserves the KubeJS listener source location when an event callback fails.
 */
@Mixin(value = EventHandlerContainer.class, remap = false)
public abstract class EventHandlerContainerMixin {
    @Shadow
    @Final
    public String source;

    @Shadow
    @Final
    public int line;

    @Redirect(
            method = "handle",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/latvian/mods/kubejs/event/EventResult$Type;exit(Ljava/lang/Object;)Ldev/latvian/mods/kubejs/event/EventExit;",
                    remap = false
            ),
            remap = false
    )
    private EventExit tsmodify$addListenerSource(EventResult.Type type, Object value) {
        if (value instanceof Throwable failure) {
            value = TypeScriptErrorReporter.eventFailure(source, line, failure);
        }
        return type.exit(value);
    }
}
