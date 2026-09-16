package com.pingsu.tsmodify.typescript.module;

import com.pingsu.tsmodify.typescript.TypeScriptTranspileException;
import com.pingsu.tsmodify.typescript.TypeScriptTranspiler;
import com.pingsu.tsmodify.typescript.cache.TranspileDiskCache;
import com.pingsu.tsmodify.typescript.cache.TranspileMetrics;
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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class TypeScriptModuleRegistry {
    private static final String SHARED_NAMESPACE = "shared";
    private static final String SHARED_FOLDER = TypeScriptScriptDirectories.SHARED_SCRIPTS;
    private static final Map<SourceKey, ModuleSource> SOURCES = new ConcurrentHashMap<>();
    /**
     * P2-2 resolution cache: {@code domain + namespace + requested path -> resolved module id}.
     *
     * <p>Resolving one import probes up to nine candidate paths with {@link Files#isRegularFile}, so
     * a modpack that imports the same module from many files repeated the same disk probes. The
     * cache lives for one script load only; {@link #clear()} drops it.</p>
     */
    private static final Map<ResolutionKey, String> RESOLVED_MODULE_IDS = new ConcurrentHashMap<>();
    /** P2-2 existence cache: module ids proven to exist on disk during this script load. */
    private static final Set<SourceKey> AVAILABLE_SOURCES = ConcurrentHashMap.newKeySet();

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
        String source = normalizeLineEndings(String.join("\n", sourceLines));
        register(domain, moduleId, modulePath, transpileModuleBody(modulePath, source));

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

    /**
     * Drops every per-load cache.
     *
     * <p>Beside the in-memory module sources this clears the resolution caches, because those
     * describe the script files that existed during the load that is ending. KubeJS calls this on
     * every script reload, which is the only supported invalidation point.</p>
     */
    public static void clear() {
        SOURCES.clear();
        RESOLVED_MODULE_IDS.clear();
        AVAILABLE_SOURCES.clear();
    }

    static String moduleId(String namespace, String path) {
        String safeNamespace = namespace == null || namespace.isBlank() ? "kubejs" : namespace;
        return safeNamespace + ":" + normalizeModulePath(path);
    }

    private static String resolveExisting(TypeScriptScriptDomain domain, String namespace, String requestedPath) {
        String normalizedPath = normalizeModulePath(requestedPath);
        ResolutionKey resolutionKey = new ResolutionKey(domain.name(), namespace, normalizedPath);
        String cached = RESOLVED_MODULE_IDS.get(resolutionKey);
        if (cached != null) {
            return cached;
        }

        List<String> candidates = candidatePaths(normalizedPath);
        for (String candidate : candidates) {
            String moduleId = namespace + ":" + candidate;
            if (isSourceAvailable(domain, moduleId)) {
                RESOLVED_MODULE_IDS.put(resolutionKey, moduleId);
                return moduleId;
            }
        }
        throw new TypeScriptTranspileException(
                "Cannot resolve TypeScript module '" + requestedPath + "' as '" + namespace + ":" + candidates.get(0) + "'");
    }

    private static boolean isSourceAvailable(TypeScriptScriptDomain domain, String moduleId) {
        SourceKey sourceKey = new SourceKey(domain.name(), moduleId);
        if (SOURCES.containsKey(sourceKey) || AVAILABLE_SOURCES.contains(sourceKey)) {
            return true;
        }

        // Only positive probes are remembered. Caching a negative would make a module that appears
        // later in the same load permanently unresolvable, which is not worth the saved stat call.
        boolean available = probeSourceAvailability(domain, moduleId);
        if (available) {
            AVAILABLE_SOURCES.add(sourceKey);
        }
        return available;
    }

    private static boolean probeSourceAvailability(TypeScriptScriptDomain domain, String moduleId) {
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
            String source = normalizeLineEndings(Files.readString(normalizedSource, StandardCharsets.UTF_8));
            String sourceName = parsed.shared()
                    ? SHARED_FOLDER + "/" + parsed.path()
                    : parsed.path();
            return new ModuleSource(sourceName, transpileModuleBody(sourceName, source));
        } catch (IOException e) {
            throw new TypeScriptTranspileException("Failed to read TypeScript module '" + moduleId + "'", e);
        }
    }

    /**
     * Single entry point for KubeJS module body transpilation.
     *
     * <p>Both call sites ({@link #preload} and {@link #loadSourceFromDisk}) must funnel through
     * here so that the disk cache and its metrics cover every transpilation and so that the source
     * text hashed for the cache key is byte-for-byte the text handed to the SWC bridge.</p>
     */
    private static String transpileModuleBody(String sourceName, String source) {
        TranspileDiskCache cache = TranspileDiskCache.shared();
        byte[] sourceBytes = source.getBytes(StandardCharsets.UTF_8);
        long startNanos = System.nanoTime();
        Optional<String> cached = cache.lookup(sourceName, sourceBytes);
        if (cached.isPresent()) {
            TranspileMetrics.record(sourceName, sourceBytes.length, System.nanoTime() - startNanos,
                    TranspileMetrics.Outcome.HIT);
            return cached.get();
        }

        String body = TypeScriptTranspiler.transpileKubeJsModuleBody(sourceName, source).code();
        long elapsedNanos = System.nanoTime() - startNanos;
        TranspileMetrics.record(sourceName, sourceBytes.length, elapsedNanos,
                cache.enabled() ? TranspileMetrics.Outcome.MISS : TranspileMetrics.Outcome.BYPASS);
        cache.store(sourceName, sourceBytes, body);
        return body;
    }

    /**
     * Collapses CRLF to LF before hashing and transpiling.
     *
     * <p>KubeJS hands {@link #preload} lines read from a reader, so that path never contains a
     * carriage return, while {@link #loadSourceFromDisk} reads raw file bytes. Normalizing both
     * lets one physical file map to exactly one cache entry regardless of the path that reached
     * it, and keeps the cache key and the transpiled text in agreement.</p>
     */
    private static String normalizeLineEndings(String source) {
        if (source.indexOf('\r') < 0) {
            return source;
        }
        return source.replace("\r\n", "\n").replace('\r', '\n');
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

    private record ResolutionKey(String domainName, String namespace, String requestedPath) {
    }

    private record ParsedModuleId(String namespace, String path) {
        private boolean shared() {
            return SHARED_NAMESPACE.equals(namespace);
        }
    }

    public record ModuleSource(String sourceName, String body) {
    }
}
