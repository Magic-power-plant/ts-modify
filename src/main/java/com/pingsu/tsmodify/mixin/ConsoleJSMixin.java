package com.pingsu.tsmodify.mixin;

import com.pingsu.tsmodify.kubejs.ConsoleJSClassFormatter;
import dev.latvian.mods.kubejs.util.ConsoleJS;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Makes the class-name suffix of {@code console.log(obj)} use the same
 * {@code class fully.qualified.Name} format as {@code console.log(obj.class)}.
 */
@Mixin(value = ConsoleJS.class, remap = false)
public abstract class ConsoleJSMixin {

    @Redirect(
            method = "line(Ldev/latvian/mods/kubejs/util/LogType;Ljava/lang/Object;Ljava/lang/Throwable;)Ldev/latvian/mods/kubejs/script/ConsoleLine;",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/lang/Class;getName()Ljava/lang/String;",
                    remap = false
            ),
            remap = false
    )
    private String tsmodify$formatClassSuffix(Class<?> cls) {
        return ConsoleJSClassFormatter.formatClassSuffix(cls);
    }
}
