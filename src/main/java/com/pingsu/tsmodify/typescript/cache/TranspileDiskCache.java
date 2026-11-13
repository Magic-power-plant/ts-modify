package com.pingsu.tsmodify.typescript.cache;

import dev.latvian.mods.kubejs.KubeJSPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Facade over the transpile disk cache.
 *
 * <p>The cache survives script reloads and game restarts, and is keyed purely by
 * ({@link TranspileCacheKey}) source content plus pipeline identity. It therefore needs no
 * invalidation protocol: editing a script changes its content hash, upgrading this mod changes the
 * pipeline fingerprint, and either one simply misses.</p>
 *
 * <p>The cache is a performance feature, never a correctness dependency. Every read and write
 * failure degrades to a miss inside {@link TranspileCacheStore}; anything that still escapes is
 * caught here and logged at DEBUG.</p>
 *
 * <p>Switch it off with {@code -Dtsmodify.transpileCache=false}. Default location is
 * {@code <kubejs>/tsmodify-cache/v<format>/}; override with {@code -Dtsmodify.transpileCacheDir}.
 * Capacity defaults to {@value #DEFAULT_MAX_ENTRIES} entries and is overridable with
 * {@code -Dtsmodify.transpileCacheMaxEntries}.</p>
 */
public final class TranspileDiskCache {
    public static final String ENABLED_PROPERTY = "tsmodify.transpileCache";
    public static final String MAX_ENTRIES_PROPERTY = "tsmodify.transpileCacheMaxEntries";
    public static final String DIRECTORY_PROPERTY = "tsmodify.transpileCacheDir";
    public static final String CACHE_DIRECTORY_NAME = "tsmodify-cache";
    public static final String CACHE_FORMAT_DIRECTORY = "v" + TranspilePipelineFingerprint.CACHE_FORMAT_VERSION;
    public static final int DEFAULT_MAX_ENTRIES = 4096;

    private static final Logger LOGGER = LoggerFactory.getLogger("TS Modify");

    private final TranspileCacheStore store;
    private final boolean enabled;

    private TranspileDiskCache(TranspileCacheStore store) {
        this.store = store;
        this.enabled = store != null;
    }

    /** Process-wide cache rooted at the KubeJS directory. */
    public static TranspileDiskCache shared() {
        return Shared.INSTANCE;
    }

    /** Cache rooted at an explicit directory; used by tests and by the directory override. */
    public static TranspileDiskCache create(Path root, int maxEntries) {
        return new TranspileDiskCache(new TranspileCacheStore(root, maxEntries));
    }

    /** No-op cache used when the feature is switched off. */
    public static TranspileDiskCache disabled() {
        return new TranspileDiskCache(null);
    }

    public boolean enabled() {
        return enabled;
    }

    /** Cache root, or {@code null} when disabled. */
    public Path root() {
        return store == null ? null : store.root();
    }

    public Optional<String> lookup(String sourceName, byte[] sourceBytes) {
        if (store == null) {
            return Optional.empty();
        }
        try {
            return store.read(TranspileCacheKey.of(sourceName, sourceBytes, TranspilePipelineFingerprint.value()));
        } catch (RuntimeException e) {
            LOGGER.debug("Ignoring TS Modify transpile cache lookup failure for {}", sourceName, e);
            return Optional.empty();
        }
    }

    public void store(String sourceName, byte[] sourceBytes, String body) {
        if (store == null || body == null || body.isEmpty()) {
            return;
        }
        try {
            store.write(TranspileCacheKey.of(sourceName, sourceBytes, TranspilePipelineFingerprint.value()), body);
        } catch (RuntimeException e) {
            LOGGER.debug("Ignoring TS Modify transpile cache store failure for {}", sourceName, e);
        }
    }

    /** Removes every cached body. Diagnostics and tests only. */
    public void clear() {
        if (store != null) {
            store.clear();
        }
    }

    /** Current entry count; diagnostics and tests only. */
    public long entryCount() {
        return store == null ? 0L : store.entryCount();
    }

    /** Cache key of one source, exposed for tests and diagnostics. */
    public TranspileCacheKey keyFor(String sourceName, byte[] sourceBytes) {
        return TranspileCacheKey.of(sourceName, sourceBytes, TranspilePipelineFingerprint.value());
    }

    private static TranspileDiskCache createShared() {
        if (!isEnabledByProperty()) {
            LOGGER.info("TS Modify transpile disk cache is disabled by -D{}=false", ENABLED_PROPERTY);
            return disabled();
        }
        try {
            Path root = resolveSharedRoot();
            TranspileDiskCache cache = create(root, resolveMaxEntries());
            // Startup probe. A miss is only actionable when the cache identity is visible: the
            // per-file DEBUG lines carry the fingerprint, and this pins the root it applies to, so a
            // cold start ("no entries yet") can be told apart from a stale one ("other fingerprint").
            LOGGER.info("TS Modify transpile disk cache at {} (fingerprint {})", root,
                    TranspilePipelineFingerprint.value());
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("TS Modify transpile disk cache holds {} cached bodies", cache.entryCount());
            }
            return cache;
        } catch (RuntimeException e) {
            LOGGER.warn("TS Modify transpile disk cache is unavailable; continuing without it", e);
            return disabled();
        }
    }

    private static Path resolveSharedRoot() {
        String override = System.getProperty(DIRECTORY_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        return KubeJSPaths.DIRECTORY.resolve(CACHE_DIRECTORY_NAME).resolve(CACHE_FORMAT_DIRECTORY);
    }

    private static boolean isEnabledByProperty() {
        String value = System.getProperty(ENABLED_PROPERTY);
        return value == null || value.isBlank() || Boolean.parseBoolean(value.trim());
    }

    private static int resolveMaxEntries() {
        String value = System.getProperty(MAX_ENTRIES_PROPERTY);
        if (value == null || value.isBlank()) {
            return DEFAULT_MAX_ENTRIES;
        }
        try {
            return Math.max(1, Integer.parseInt(value.trim()));
        } catch (NumberFormatException e) {
            LOGGER.warn("Ignoring invalid -D{}=\"{}\"; using {}", MAX_ENTRIES_PROPERTY, value, DEFAULT_MAX_ENTRIES);
            return DEFAULT_MAX_ENTRIES;
        }
    }

    private static final class Shared {
        private static final TranspileDiskCache INSTANCE = createShared();

        private Shared() {
        }
    }
}
