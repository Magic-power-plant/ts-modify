package com.pingsu.tsmodify.mixin;

import com.pingsu.tsmodify.probejs.ProbeJSTypeInfoResolver;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProbeJSClassInfoMixinTest {
    @TempDir
    Path tempDir;

    @Test
    void preservesResolvableGenericMetadata() {
        assertArrayEquals(
                ArrayList.class.getGenericInterfaces(),
                ProbeJSTypeInfoResolver.safeGenericInterfaces(ArrayList.class));
        assertEquals(
                ArrayList.class.getGenericSuperclass(),
                ProbeJSTypeInfoResolver.safeGenericSuperclass(ArrayList.class));
    }

    @Test
    void fallsBackToRawTypesWhenGenericSignatureReferencesMissingClass() throws Exception {
        Path classes = compileBrokenGenericSignatures();
        Files.delete(classes.resolve("fixture/Missing.class"));

        try (URLClassLoader loader = new URLClassLoader(
                new java.net.URL[]{classes.toUri().toURL()},
                null)) {
            Class<?> brokenInterfaces = Class.forName("fixture.BrokenInterfaces", false, loader);
            Class<?> brokenSuperclass = Class.forName("fixture.BrokenSuperclass", false, loader);

            assertThrows(TypeNotPresentException.class, brokenInterfaces::getGenericInterfaces);
            assertThrows(TypeNotPresentException.class, brokenSuperclass::getGenericSuperclass);
            assertArrayEquals(
                    brokenInterfaces.getInterfaces(),
                    ProbeJSTypeInfoResolver.safeGenericInterfaces(brokenInterfaces));
            assertEquals(
                    brokenSuperclass.getSuperclass(),
                    ProbeJSTypeInfoResolver.safeGenericSuperclass(brokenSuperclass));
        }
    }

    private Path compileBrokenGenericSignatures() throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Tests require a JDK, not a JRE");

        Path source = tempDir.resolve("GenericFixtures.java");
        Path classes = tempDir.resolve("classes");
        Files.createDirectories(classes);
        Files.writeString(source, """
                package fixture;

                interface Marker<T> {
                }

                class Missing {
                }

                class BrokenInterfaces implements Marker<Missing> {
                }

                class Parent<T> {
                }

                class BrokenSuperclass extends Parent<Missing> {
                }
                """, StandardCharsets.UTF_8);

        int result = compiler.run(
                null,
                null,
                null,
                "-encoding",
                "UTF-8",
                "-d",
                classes.toString(),
                source.toString());
        assertEquals(0, result, "Failed to compile generic-signature fixture");
        return classes;
    }
}
