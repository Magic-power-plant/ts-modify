package com.pingsu.tsmodify.typescript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.caoccao.javet.swc4j.Swc4j;
import java.net.URL;
import java.util.List;
import org.junit.jupiter.api.Test;

class Swc4jVendoredRuntimeTest {
    private static final List<String> NATIVE_RESOURCE_NAMES = List.of(
            "/libswc4j-linux-arm64.v.2.1.0.so",
            "/libswc4j-linux-x86_64.v.2.1.0.so",
            "/libswc4j-macos-arm64.v.2.1.0.dylib",
            "/libswc4j-macos-x86_64.v.2.1.0.dylib",
            "/libswc4j-windows-arm64.v.2.1.0.dll",
            "/libswc4j-windows-x86_64.v.2.1.0.dll");

    @Test
    void loadsVendoredJavaApiAndWasmRuntimeWithoutNativeLibraries() {
        URL swc4jClassLocation = Swc4j.class.getProtectionDomain().getCodeSource().getLocation();
        String classLocation = swc4jClassLocation.toString();

        assertFalse(classLocation.contains("swc4j-2.1.0.jar"), classLocation);
        NATIVE_RESOURCE_NAMES.forEach(resourceName -> assertNull(Swc4j.class.getResource(resourceName), resourceName));
        assertNotNull(Swc4j.class.getResource("/swc4j/swc4j_bridge.wasm"));
        assertEquals("2.1.0", new Swc4j().getVersion());
    }
}
