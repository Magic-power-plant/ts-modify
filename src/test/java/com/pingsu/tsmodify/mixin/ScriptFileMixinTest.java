package com.pingsu.tsmodify.mixin;

import dev.latvian.mods.kubejs.script.ScriptFile;
import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.Scriptable;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.Redirect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptFileMixinTest {
    private static final String EVALUATE_STRING_DESCRIPTOR =
            "Ldev/latvian/mods/rhino/Context;evaluateString(Ldev/latvian/mods/rhino/Scriptable;Ljava/lang/String;Ljava/lang/String;ILjava/lang/Object;)Ljava/lang/Object;";

    @Test
    void redirectMatchesPinnedKubeJsScriptLoadInvocation() throws NoSuchMethodException {
        Method scriptLoad = ScriptFile.class.getDeclaredMethod("load");
        Method redirect = ScriptFileMixin.class.getDeclaredMethod(
                "tsmodify$evaluateCachedScript",
                Context.class,
                Scriptable.class,
                String.class,
                String.class,
                int.class,
                Object.class);
        Redirect annotation = redirect.getAnnotation(Redirect.class);

        assertNotNull(scriptLoad);
        assertNotNull(annotation);
        assertEquals("load", annotation.method()[0]);
        assertEquals("INVOKE", annotation.at().value());
        assertEquals(EVALUATE_STRING_DESCRIPTOR, annotation.at().target());
        assertEquals(Object.class, redirect.getReturnType());
        assertNotNull(Context.class.getMethod(
                "evaluateString",
                Scriptable.class,
                String.class,
                String.class,
                int.class,
                Object.class));
    }

    @Test
    void mixinConfigurationRegistersTheScriptFileRedirect() throws Exception {
        InputStream resource = ScriptFileMixinTest.class.getClassLoader().getResourceAsStream("tsmodify.mixins.json");
        assertNotNull(resource);
        String configuration;
        try (resource) {
            configuration = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertTrue(configuration.contains("\"ScriptFileMixin\""));
    }
}
