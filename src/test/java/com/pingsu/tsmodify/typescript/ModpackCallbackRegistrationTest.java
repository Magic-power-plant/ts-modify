package com.pingsu.tsmodify.typescript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.pingsu.tsmodify.typescript.module.TypeScriptModuleRegistry;
import com.pingsu.tsmodify.typescript.module.TypeScriptModuleRuntime;
import com.pingsu.tsmodify.typescript.module.TypeScriptScriptDomain;
import dev.latvian.mods.kubejs.event.IEventHandler;
import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.ScriptableObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real modpack source snapshots; verifies registration, not Minecraft registry/event behavior. */
class ModpackCallbackRegistrationTest {
    private static final Path FIXTURES = Path.of("test", "callback_interop", "kubejs");

    @TempDir
    Path tempDir;

    @AfterEach
    void clearRegistry() {
        TypeScriptModuleRegistry.clear();
    }

    @Test
    void registersAllRealTypeScriptStartupScripts() throws IOException {
        TypeScriptScriptDomain domain = new TypeScriptScriptDomain("startup", tempDir.resolve("startup_scripts"));
        Context context = Context.enter();
        var scope = context.initStandardObjects();
        RegistrationProbe probe = new RegistrationProbe();
        ScriptableObject.putProperty(scope, "StartupEvents", Context.javaToJS(context, probe, scope), context);
        TypeScriptModuleRuntime runtime = new TypeScriptModuleRuntime(domain, context, scope);

        for (String file : List.of("item.ts", "enchanted/rite.ts", "enchanted/rite_shape.ts")) {
            register(domain, "kubejs:" + file, "startup_scripts/" + file);
            runtime.requireSelf("kubejs:" + file, scope);
        }

        assertEquals(List.of("item", "enchanted:circle_magic/rite", "enchanted:circle_magic/shape"), probe.events);
        assertEquals(3, probe.handlers.size());
    }

    @Test
    void registersRealTooltipsAfterLoadingTheirSharedImport() throws IOException {
        TypeScriptScriptDomain domain = new TypeScriptScriptDomain("client", tempDir.resolve("client_scripts"));
        Context context = Context.enter();
        var scope = context.initStandardObjects();
        RegistrationProbe probe = new RegistrationProbe();
        ScriptableObject.putProperty(scope, "ItemEvents", Context.javaToJS(context, probe, scope), context);
        context.evaluateString(scope, "var console = { log: function() {} };", "bindings.js", 1, null);
        register(domain, "shared:list.ts", "shared_scripts/list.ts");
        register(domain, "kubejs:tooltips.ts", "client_scripts/tooltips.ts");

        new TypeScriptModuleRuntime(domain, context, scope).requireSelf("kubejs:tooltips.ts", scope);

        assertEquals(List.of("tooltip"), probe.events);
        assertEquals(1, probe.handlers.size());
    }

    private static void register(TypeScriptScriptDomain domain, String moduleId, String file) throws IOException {
        String source = Files.readString(FIXTURES.resolve(file), StandardCharsets.UTF_8);
        String body = TypeScriptTranspiler.transpileKubeJsModuleBody(file, source).code();
        TypeScriptModuleRegistry.register(domain, moduleId, file, body);
    }

    public static final class RegistrationProbe {
        public final String modelProperties = "unused";
        private final List<String> events = new ArrayList<>();
        private final List<IEventHandler> handlers = new ArrayList<>();

        public void registry(String registry, IEventHandler handler) {
            assertNotNull(handler);
            events.add(registry);
            handlers.add(handler);
        }

        public void tooltip(IEventHandler handler) {
            registry("tooltip", handler);
        }
    }
}
