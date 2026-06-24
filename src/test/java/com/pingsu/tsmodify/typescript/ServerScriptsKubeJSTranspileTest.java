package com.pingsu.tsmodify.typescript;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.EvaluatorException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ServerScriptsKubeJSTranspileTest {
    private static final Path SERVER_SCRIPTS_ROOT = Path.of("test", "server_scripts");
    private static final Pattern RESIDUAL_TYPESCRIPT_SYNTAX = Pattern.compile(
            "(?m)^\\s*(?:import|export|interface|declare|namespace)\\b"
                    + "|^\\s*type\\s+[A-Za-z_$][\\w$]*(?:\\s*<[^>]*>)?\\s*="
                    + "|\\bsatisfies\\b");

    @Test
    void allServerScriptsTranspileToRhinoCompatibleJavaScript() throws IOException {
        List<String> failures = new ArrayList<>();
        List<Path> scriptPaths = collectServerScriptPaths();
        Context context = Context.enter();

        for (Path scriptPath : scriptPaths) {
            String relativeFileName = SERVER_SCRIPTS_ROOT.relativize(scriptPath).toString().replace('\\', '/');
            String source = Files.readString(scriptPath, StandardCharsets.UTF_8);

            try {
                TypeScriptSourceTranspileResult result = TypeScriptTranspiler.transpileSource(
                        relativeFileName,
                        source,
                        TypeScriptTranspileConfig.KUBEJS);
                validateTranspiledScript(context, relativeFileName, result.code(), failures);
            } catch (RuntimeException e) {
                failures.add(relativeFileName + ": TypeScript conversion failed: " + e.getMessage());
            }
        }

        assertTrue(
                failures.isEmpty(),
                () -> "Failed to convert " + failures.size() + " of " + scriptPaths.size()
                        + " server script(s):%n".formatted() + String.join(System.lineSeparator(), failures));
    }

    private static List<Path> collectServerScriptPaths() throws IOException {
        try (Stream<Path> stream = Files.walk(SERVER_SCRIPTS_ROOT)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> TypeScriptTranspiler.isTranspilableTypeScriptFile(path.toString()))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
    }

    private static void validateTranspiledScript(
            Context context,
            String relativeFileName,
            String code,
            List<String> failures) {
        if (RESIDUAL_TYPESCRIPT_SYNTAX.matcher(code).find()) {
            failures.add(relativeFileName + ": converted output still contains TypeScript/module syntax");
        }

        try {
            context.compileString(code, relativeFileName, 1, null);
        } catch (EvaluatorException e) {
            failures.add(relativeFileName + ": Rhino rejected converted output: " + e.getMessage());
        } catch (RuntimeException e) {
            failures.add(relativeFileName + ": Rhino compatibility check failed: " + e.getMessage());
        }
    }
}
