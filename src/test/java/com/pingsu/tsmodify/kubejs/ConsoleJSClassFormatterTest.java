package com.pingsu.tsmodify.kubejs;

import dev.latvian.mods.kubejs.util.ConsoleJS;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ConsoleJSClassFormatterTest {

    @Test
    void plainClassUsesClassKeywordPrefix() {
        assertEquals("class java.lang.Object", ConsoleJSClassFormatter.formatClassSuffix(Object.class));
        assertEquals("class java.lang.String", ConsoleJSClassFormatter.formatClassSuffix(String.class));
        assertEquals(
                "class dev.latvian.mods.kubejs.util.ConsoleJS",
                ConsoleJSClassFormatter.formatClassSuffix(ConsoleJS.class));
    }

    @Test
    void interfaceUsesInterfaceKeywordPrefix() {
        assertEquals("interface java.lang.Runnable", ConsoleJSClassFormatter.formatClassSuffix(Runnable.class));
        assertEquals("interface java.util.List", ConsoleJSClassFormatter.formatClassSuffix(java.util.List.class));
    }

    @Test
    void arrayClassUsesJvmBinaryName() {
        assertEquals("class [Ljava.lang.String;", ConsoleJSClassFormatter.formatClassSuffix(String[].class));
        assertEquals("class [I", ConsoleJSClassFormatter.formatClassSuffix(int[].class));
        assertEquals("class [[Ljava.lang.Object;", ConsoleJSClassFormatter.formatClassSuffix(Object[][].class));
    }

    @Test
    void primitiveClassUsesKeywordPrefix() {
        assertEquals("int", ConsoleJSClassFormatter.formatClassSuffix(int.class));
        assertEquals("void", ConsoleJSClassFormatter.formatClassSuffix(void.class));
    }

    @Test
    void anonymousClassKeepsBinaryName() {
        Object anonymous = new Object() {
        };
        assertEquals("class " + anonymous.getClass().getName(), ConsoleJSClassFormatter.formatClassSuffix(anonymous.getClass()));
    }

    @Test
    void nullClassFallsBackToNullText() {
        assertEquals("null", ConsoleJSClassFormatter.formatClassSuffix(null));
    }

    @Test
    void redirectTargetsTheOnlyClassGetNameCallInLine() throws NoSuchMethodException {
        Method line = ConsoleJS.class.getDeclaredMethod(
                "line",
                dev.latvian.mods.kubejs.util.LogType.class,
                Object.class,
                Throwable.class);
        Method handler = com.pingsu.tsmodify.mixin.ConsoleJSMixin.class.getDeclaredMethod(
                "tsmodify$formatClassSuffix",
                Class.class);
        org.spongepowered.asm.mixin.injection.Redirect annotation =
                handler.getAnnotation(org.spongepowered.asm.mixin.injection.Redirect.class);

        assertNotNull(line);
        assertNotNull(annotation);
        assertEquals("INVOKE", annotation.at().value());
        assertEquals("Ljava/lang/Class;getName()Ljava/lang/String;", annotation.at().target());
        assertEquals(String.class, handler.getReturnType());
    }
}
