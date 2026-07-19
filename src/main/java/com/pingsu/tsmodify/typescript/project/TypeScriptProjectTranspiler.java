package com.pingsu.tsmodify.typescript.project;

import com.pingsu.tsmodify.typescript.TypeScriptErrorReporter;
import com.pingsu.tsmodify.typescript.TypeScriptSourceTranspileResult;
import com.pingsu.tsmodify.typescript.TypeScriptTranspileConfig;
import com.pingsu.tsmodify.typescript.TypeScriptTranspileException;
import com.pingsu.tsmodify.typescript.TypeScriptTranspiler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public final class TypeScriptProjectTranspiler {
    private TypeScriptProjectTranspiler() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2 || args.length > 3) {
            System.err.println("Usage: TypeScriptProjectTranspiler <sourceRoot> <outputRoot> [--continue]");
            System.exit(2);
        }

        TypeScriptTranspileConfig config = TypeScriptTranspileConfig.PROJECT;
        if (args.length == 3 && "--continue".equals(args[2])) {
            config = config.toBuilder().failFast(false).build();
        }

        TypeScriptProjectTranspileResult result = transpileProject(Path.of(args[0]), Path.of(args[1]), config);
        System.out.println("Processed " + result.files().size() + " file(s); transpiled "
                + result.transpiledFileCount() + " TypeScript file(s).");
        if (!result.successful()) {
            result.failures().forEach(failure -> System.err.println(failure.getMessage()));
            System.exit(1);
        }
    }

    public static TypeScriptProjectTranspileResult transpileProject(Path sourceRoot, Path outputRoot)
            throws IOException {
        return transpileProject(sourceRoot, outputRoot, TypeScriptTranspileConfig.PROJECT);
    }

    public static TypeScriptProjectTranspileResult transpileProject(
            Path sourceRoot,
            Path outputRoot,
            TypeScriptTranspileConfig config) throws IOException {
        Path normalizedSourceRoot = sourceRoot.toAbsolutePath().normalize();
        Path normalizedOutputRoot = outputRoot.toAbsolutePath().normalize();
        validateRoots(normalizedSourceRoot, normalizedOutputRoot);

        List<TypeScriptFileTranspileResult> files = new ArrayList<>();
        List<TypeScriptTranspileException> failures = new ArrayList<>();
        for (Path sourcePath : collectSourceFiles(normalizedSourceRoot)) {
            try {
                TypeScriptFileTranspileResult result = transpileFile(
                        normalizedSourceRoot,
                        normalizedOutputRoot,
                        sourcePath,
                        config);
                if (result != null) {
                    files.add(result);
                }
            } catch (TypeScriptTranspileException failure) {
                if (config.failFast()) {
                    throw failure;
                }
                failures.add(failure);
            }
        }

        return new TypeScriptProjectTranspileResult(files, failures);
    }

    private static TypeScriptFileTranspileResult transpileFile(
            Path sourceRoot,
            Path outputRoot,
            Path sourcePath,
            TypeScriptTranspileConfig config) {
        Path relativePath = sourceRoot.relativize(sourcePath);
        String relativeFileName = relativePath.toString();

        try {
            if (TypeScriptTranspiler.isTranspilableTypeScriptFile(relativeFileName)) {
                String source = Files.readString(sourcePath, StandardCharsets.UTF_8);
                TypeScriptSourceTranspileResult output = TypeScriptTranspiler.transpileSource(
                        relativeFileName,
                        source,
                        config);
                Path outputPath = outputRoot.resolve(TypeScriptTranspiler.outputFileName(relativeFileName));
                writeString(outputPath, output.code());
                if (output.sourceMap() != null) {
                    writeString(Path.of(outputPath.toString() + ".map"), output.sourceMap());
                }
                return new TypeScriptFileTranspileResult(sourcePath, outputPath, true, output.sourceMap());
            }

            if (TypeScriptTranspiler.isTypeScriptDeclarationFile(relativeFileName)) {
                return null;
            }

            if (!config.copyNonTypeScriptFiles()) {
                return null;
            }

            Path outputPath = outputRoot.resolve(relativePath);
            Files.createDirectories(outputPath.getParent());
            Files.copy(sourcePath, outputPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            return new TypeScriptFileTranspileResult(sourcePath, outputPath, false, null);
        } catch (IOException | RuntimeException e) {
            throw TypeScriptErrorReporter.projectFileFailure(sourcePath, e);
        }
    }

    private static void writeString(Path outputPath, String value) throws IOException {
        Files.createDirectories(outputPath.getParent());
        Files.writeString(outputPath, value, StandardCharsets.UTF_8);
    }

    private static List<Path> collectSourceFiles(Path sourceRoot) throws IOException {
        try (Stream<Path> stream = Files.walk(sourceRoot)) {
            return stream.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
    }

    private static void validateRoots(Path sourceRoot, Path outputRoot) throws IOException {
        if (!Files.isDirectory(sourceRoot)) {
            throw new IOException("TypeScript source root does not exist or is not a directory: " + sourceRoot);
        }
        if (outputRoot.equals(sourceRoot) || outputRoot.startsWith(sourceRoot)) {
            throw new IOException("TypeScript output root must not be the source root or a child of it: " + outputRoot);
        }
    }
}
