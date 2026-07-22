package com.pingsu.tsmodify.mixin;

import com.pingsu.tsmodify.probejs.ProbeJSTypeInfoResolver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.lang.reflect.Type;

/**
 * Keeps ProbeJS dumps running when optional Mod APIs are missing from a generic signature.
 */
@Pseudo
@Mixin(targets = "com.probejs.jdoc.java.ClassInfo", remap = false)
public abstract class ProbeJSClassInfoMixin {
    @Redirect(
            method = "getInterfaceTypes",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/lang/Class;getGenericInterfaces()[Ljava/lang/reflect/Type;"
            ),
            require = 0,
            remap = false
    )
    private Type[] tsmodify$safeGenericInterfaces(Class<?> clazz) {
        return ProbeJSTypeInfoResolver.safeGenericInterfaces(clazz);
    }

    @Redirect(
            method = "getSuperClassType",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/lang/Class;getGenericSuperclass()Ljava/lang/reflect/Type;"
            ),
            require = 0,
            remap = false
    )
    private Type tsmodify$safeGenericSuperclass(Class<?> clazz) {
        return ProbeJSTypeInfoResolver.safeGenericSuperclass(clazz);
    }
}
