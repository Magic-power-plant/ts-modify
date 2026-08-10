package com.pingsu.tsmodify.mixin;

import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.Script;
import dev.latvian.mods.rhino.Scriptable;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.Redirect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextEvaluateStringMixinTest {
    private static final String COMPILE_STRING_DESCRIPTOR =
            "Ldev/latvian/mods/rhino/Context;compileString(Ljava/lang/String;Ljava/lang/String;ILjava/lang/Object;)Ldev/latvian/mods/rhino/Script;";

    @Test
    void redirectMatchesPinnedRhinoEvaluateStringCompilation() throws NoSuchMethodException {
        Method evaluateString = Context.class.getMethod(
                "evaluateString",
                Scriptable.class,
                String.class,
                String.class,
                int.class,
                Object.class);
        Method redirect = ContextEvaluateStringMixin.class.getDeclaredMethod(
                "tsmodify$compileCachedScript",
                Context.class,
                String.class,
                String.class,
                int.class,
                Object.class);
        Redirect annotation = redirect.getAnnotation(Redirect.class);

        assertNotNull(evaluateString);
        assertNotNull(annotation);
        assertEquals("evaluateString", annotation.method()[0]);
        assertEquals("INVOKE", annotation.at().value());
        assertEquals(COMPILE_STRING_DESCRIPTOR, annotation.at().target());
        assertEquals(Script.class, redirect.getReturnType());
        assertNotNull(Context.class.getMethod(
                "compileString",
                String.class,
                String.class,
                int.class,
                Object.class));
    }

    @Test
    void mixinConfigurationRegistersTheContextRedirect() throws Exception {
        InputStream resource = ContextEvaluateStringMixinTest.class.getClassLoader().getResourceAsStream("tsmodify.mixins.json");
        assertNotNull(resource);
        String configuration;
        try (resource) {
            configuration = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertTrue(configuration.contains("\"ContextEvaluateStringMixin\""));
    }
}
