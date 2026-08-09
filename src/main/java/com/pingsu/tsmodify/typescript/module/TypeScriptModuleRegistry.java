package com.pingsu.tsmodify.typescript.module;

import com.pingsu.tsmodify.typescript.TypeScriptSourceTranspileResult;
import com.pingsu.tsmodify.typescript.TypeScriptTranspileException;
import com.pingsu.tsmodify.typescript.TypeScriptTranspiler;
import dev.latvian.mods.kubejs.script.ScriptFileInfo;
import dev.latvian.mods.kubejs.script.ScriptSource;
import dev.latvian.mods.kubejs.script.ScriptType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class TypeScriptModuleRegistry {
    private static final String SHARED_NAMESPACE = "shared";
    private static final String SHARED_FOLDER = TypeScriptScriptDirectories.SHARED_SCRIPTS;
    private static final Map<SourceKey, ModuleSource> SOURCES = new ConcurrentHashMap<>();

    private TypeScriptModuleRegistry() {
    }

    public static ScriptType inferScriptType(ScriptSource source, ScriptFileInfo info) {
        if (source instanceof ScriptSource.FromPath fromPath) {
            Path path = fromPath.getPath(info).toAbsolutePath().normalize();
            for (ScriptType scriptType : ScriptType.VALUES) {
                Path root = scriptType.path.toAbsolutePath().normalize();
                if (path.startsWith(root)) {
                    return scriptType;
                }
            }
        }

        String fileName = normalizeSeparators(info.file);
        for (ScriptType scriptType : ScriptType.VALUES) {
            if (fileName.startsWith(scriptType.name + "_scripts/")) {
                return scriptType;
            }
        }
        return null;
    }

    public static List<String> preload(
            ScriptType scriptType,
            String namespace,
            String fileName,
            List<String> sourceLines) {
        return preload(TypeScriptScriptDomain.from(scriptType), namespace, scriptType.name, fileName, sourceLines);
    }

    public static List<String> preload(
            TypeScriptScriptDomain domain,
            String namespace,
            String scriptTypeName,
            String fileName,
            List<String> sourceLines) {
        if (!TypeScriptTranspiler.isTranspilableTypeScriptFile(fileName)) {
            return sourceLines;
        }

        String modulePath = normalizeScriptPath(scriptTypeName, fileName);
        String moduleId = moduleId(namespace, modulePath);
        String source = String.join("\n", sourceLines);
        TypeScriptSourceTranspileResult result = TypeScriptTranspiler.transpileKubeJsModuleBody(modulePath, source);
        register(domain, moduleId, modulePath, result.code());

        List<String> entryLines = new ArrayList<>();
        for (String line : sourceLines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("//")) {
                entryLines.add(line);
            }
        }
        entryLines.add("__tsmodify_modules.requireSelf(\"" + escapeJavaScript(moduleId) + "\", this);");
        return entryLines;
    }

    public static void register(ScriptType scriptType, String moduleId, String sourceName, String body) {
        register(TypeScriptScriptDomain.from(scriptType), moduleId, sourceName, body);
    }

    public static void register(TypeScriptScriptDomain domain, String moduleId, String sourceName, String body) {
        SOURCES.put(new SourceKey(domain.name(), moduleId), new ModuleSource(sourceName, body));
    }

    public static ModuleSource getSource(ScriptType scriptType, String moduleId) {
        return getSource(TypeScriptScriptDomain.from(scriptType), moduleId);
    }

    public static ModuleSource getSource(TypeScriptScriptDomain domain, String moduleId) {
        SourceKey key = new SourceKey(domain.name(), moduleId);
        ModuleSource source = SOURCES.get(key);
        if (source != null) {
            return source;
        }

        source = loadSourceFromDisk(domain, moduleId);
        if (source != null) {
            SOURCES.put(key, source);
        }
        return source;
    }

    public static String resolve(ScriptType scriptType, String requesterId, String specifier) {
        return resolve(TypeScriptScriptDomain.from(scriptType), requesterId, specifier);
    }

    public static String resolve(TypeScriptScriptDomain domain, String requesterId, String specifier) {
        ParsedModuleId requester = parseModuleId(requesterId);
        String rawSpecifier = stripSpecifierSuffix(specifier);
        if (rawSpecifier.isBlank()) {
            throw new TypeScriptTranspileException("Empty TypeScript module specifier in '" + requesterId + "'");
        }

        if (rawSpecifier.startsWith(SHARED_NAMESPACE + ":")) {
            return resolveExisting(domain, SHARED_NAMESPACE, rawSpecifier.substring((SHARED_NAMESPACE + ":").length()));
        }

        if (!rawSpecifier.startsWith(".") && !rawSpecifier.startsWith("/")) {
            throw new TypeScriptTranspileException(
                    "Unsupported bare TypeScript module specifier '" + specifier + "' in '" + requesterId + "'");
        }

        Path typeRoot = domain.root().toAbsolutePath().normalize();
        Path sharedRoot = domain.sharedRoot().toAbsolutePath().normalize();
        Path requesterRoot = requester.shared() ? sharedRoot : typeRoot;
        Path requesterPath = requesterRoot.resolve(requester.path()).normalize();
        Path base = requesterPath.getParent() == null ? requesterRoot : requesterPath.getParent();
        Path kubejsRoot = typeRoot.getParent() == null ? typeRoot : typeRoot.getParent();
        Path target = rawSpecifier.startsWith("/")
                ? kubejsRoot.resolve(rawSpecifier.substring(1)).normalize()
                : base.resolve(rawSpecifier).normalize();

        if (target.startsWith(sharedRoot)) {
            return resolveExisting(domain, SHARED_NAMESPACE, toModulePath(sharedRoot.relativize(target)));
        }
        if (target.startsWith(typeRoot)) {
            return resolveExisting(domain, requester.namespace(), toModulePath(typeRoot.relativize(target)));
        }

        throw new TypeScriptTranspileException(
                "TypeScript module '" + specifier + "' from '" + requesterId
                        + "' resolves outside " + domain.name() + " and shared script roots");
    }

    public static void clear() {
        SOURCES.clear();
    }

    static String moduleId(String namespace, String path) {
        String safeNamespace = namespace == null || namespace.isBlank() ? "kubejs" : namespace;
        return safeNamespace + ":" + normalizeModulePath(path);
    }

    private static String resolveExisting(TypeScriptScriptDomain domain, String namespace, String requestedPath) {
        String normalizedPath = normalizeModulePath(requestedPath);
        for (String candidate : candidatePaths(normalizedPath)) {
            String moduleId = namespace + ":" + candidate;
            if (isSourceAvailable(domain, moduleId)) {
                return moduleId;
            }
        }
        String firstCandidate = candidatePaths(normalizedPath).get(0);
        throw new TypeScriptTranspileException(
                "Cannot resolve TypeScript module '" + requestedPath + "' as '" + namespace + ":" + firstCandidate + "'");
    }

    private static boolean isSourceAvailable(TypeScriptScriptDomain domain, String moduleId) {
        if (SOURCES.containsKey(new SourceKey(domain.name(), moduleId))) {
            return true;
        }

        ParsedModuleId parsed = parseModuleId(moduleId);
        if (!TypeScriptTranspiler.isTranspilableTypeScriptFile(parsed.path())) {
            return false;
        }
        Path root = parsed.shared() ? domain.sharedRoot() : domain.root();
        Path path = root.resolve(parsed.path()).normalize();
        return Files.isRegularFile(path);
    }

    private static ModuleSource loadSourceFromDisk(TypeScriptScriptDomain domain, String moduleId) {
        ParsedModuleId parsed = parseModuleId(moduleId);
        Path root = parsed.shared() ? domain.sharedRoot() : domain.root();
        Path sourcePath = root.resolve(parsed.path()).normalize();
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedSource = sourcePath.toAbsolutePath().normalize();
        if (!normalizedSource.startsWith(normalizedRoot)
                || !TypeScriptTranspiler.isTranspilableTypeScriptFile(normalizedSource.toString())
                || !Files.isRegularFile(normalizedSource)) {
            return null;
        }

        try {
            String source = Files.readString(normalizedSource, StandardCharsets.UTF_8);
            String sourceName = parsed.shared()
                    ? SHARED_FOLDER + "/" + parsed.path()
                    : parsed.path();
            String body = TypeScriptTranspiler.transpileKubeJsModuleBody(sourceName, source).code();
            return new ModuleSource(sourceName, body);
        } catch (IOException e) {
            throw new TypeScriptTranspileException("Failed to read TypeScript module '" + moduleId + "'", e);
        }
    }

    private static List<String> candidatePaths(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        List<String> candidates = new ArrayList<>();
        candidates.add(path);
        if (lower.endsWith(".js")) {
            candidates.add(path.substring(0, path.length() - ".js".length()) + ".ts");
            candidates.add(path.substring(0, path.length() - ".js".length()) + ".tsx");
        } else if (lower.endsWith(".mjs")) {
            candidates.add(path.substring(0, path.length() - ".mjs".length()) + ".mts");
        } else if (lower.endsWith(".cjs")) {
            candidates.add(path.substring(0, path.length() - ".cjs".length()) + ".cts");
        } else if (!hasKnownExtension(lower)) {
            candidates.add(path + ".ts");
            candidates.add(path + ".tsx");
            candidates.add(path + ".mts");
            candidates.add(path + ".cts");
            candidates.add(path + "/index.ts");
            candidates.add(path + "/index.tsx");
            candidates.add(path + "/index.mts");
            candidates.add(path + "/index.cts");
        }
        return candidates;
    }

    private static boolean hasKnownExtension(String lower) {
        return lower.endsWith(".ts")
                || lower.endsWith(".tsx")
                || lower.endsWith(".mts")
                || lower.endsWith(".cts")
                || lower.endsWith(".js")
                || lower.endsWith(".mjs")
                || lower.endsWith(".cjs");
    }

    private static String normalizeScriptPath(String scriptTypeName, String fileName) {
        String path = normalizeSeparators(fileName);
        String folder = scriptTypeName + "_scripts/";
        if (path.startsWith("kubejs/" + folder)) {
            path = path.substring(("kubejs/" + folder).length());
        } else if (path.startsWith(folder)) {
            path = path.substring(folder.length());
        }
        return normalizeModulePath(path);
    }

    private static String normalizeModulePath(String path) {
        String normalized = normalizeSeparators(path);
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private static String normalizeSeparators(String path) {
        return path.replace('\\', '/');
    }

    private static String toModulePath(Path path) {
        return normalizeModulePath(path.toString());
    }

    private static String stripSpecifierSuffix(String specifier) {
        int end = specifier.length();
        int query = specifier.indexOf('?');
        int hash = specifier.indexOf('#');
        if (query >= 0) {
            end = Math.min(end, query);
        }
        if (hash >= 0) {
            end = Math.min(end, hash);
        }
        return normalizeSeparators(specifier.substring(0, end));
    }

    static String escapeJavaScript(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static ParsedModuleId parseModuleId(String moduleId) {
        int separator = moduleId.indexOf(':');
        if (separator <= 0 || separator == moduleId.length() - 1) {
            throw new TypeScriptTranspileException("Invalid TypeScript module id '" + moduleId + "'");
        }
        String namespace = moduleId.substring(0, separator);
        String path = normalizeModulePath(moduleId.substring(separator + 1));
        return new ParsedModuleId(namespace, path);
    }

    private record SourceKey(String domainName, String moduleId) {
    }

    private record ParsedModuleId(String namespace, String path) {
        private boolean shared() {
            return SHARED_NAMESPACE.equals(namespace);
        }
    }

    public record ModuleSource(String sourceName, String body) {
    }
}
