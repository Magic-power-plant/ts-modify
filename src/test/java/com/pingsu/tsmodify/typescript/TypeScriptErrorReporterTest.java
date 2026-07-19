package com.pingsu.tsmodify.typescript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class TypeScriptErrorReporterTest {
    @Test
    void includesSourceLocationAndCodeFrame() {
        String source = "const first = 1;\nconst broken = ;\nconst last = 3;";
        TypeScriptTranspileException failure = TypeScriptErrorReporter.transpileFailure(
                "server_scripts/broken.ts",
                source,
                "SWC transpilation",
                new IllegalArgumentException("Unexpected token at 2:15"));

        assertEquals("SWC transpilation", failure.context().phase());
        assertEquals("server_scripts/broken.ts", failure.context().sourceName());
        assertEquals(2, failure.context().line());
        assertEquals(15, failure.context().column());
        assertTrue(failure.getMessage().contains("line 2, column 15"));
        assertTrue(failure.getMessage().contains("2 | const broken = ;"));
        assertTrue(failure.getMessage().contains("^"));
    }

    @Test
    void includesModuleRequestChain() {
        TypeScriptTranspileException failure = TypeScriptErrorReporter.moduleExecutionFailure(
                "pack:consumer.ts",
                "server_scripts/consumer.ts",
                "require(\"./missing\");",
                List.of("pack:consumer.ts", "pack:missing.ts"),
                new IllegalStateException("module failed"));

        assertTrue(failure.getMessage().contains("module=pack:consumer.ts"));
        assertTrue(failure.getMessage().contains("pack:consumer.ts -> pack:missing.ts"));
        assertEquals(2, failure.context().moduleChain().size());
    }

    @Test
    void includesKubeJsListenerRegistrationLocation() {
        TypeScriptTranspileException failure = TypeScriptErrorReporter.eventFailure(
                "server_scripts/machine/crafter.ts",
                238,
                new IllegalStateException("event callback failed"));

        assertEquals("KubeJS event callback", failure.context().phase());
        assertEquals(238, failure.context().line());
        assertTrue(failure.getMessage().contains("source=server_scripts/machine/crafter.ts"));
        assertTrue(failure.getMessage().contains("line 238"));
    }
}
