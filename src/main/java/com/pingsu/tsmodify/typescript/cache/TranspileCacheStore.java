package com.pingsu.tsmodify.typescript.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * File system details of the transpile disk cache.
 *
 * <p>Layout: {@code <root>/<first two key characters>/<key>.js}, one file per transpiled body,
 * UTF-8, no source map. Discovery is content addressed, so no index file is needed.</p>
 *
 * <p>Writes go to a uniquely named temporary file in the cache root and are published with
 * {@link StandardCopyOption#ATOMIC_MOVE}, so a crash or a concurrent reader can never observe a
 * half written body. A hit refreshes the entry's last-modified time, which makes capacity eviction
 * an approximate LRU over file access times.</p>
 *
 * <p>Every operation is allowed to fail. A failing cache is only a slower cache: the caller
 * degrades to re-transpiling.</p>
 */
final class TranspileCacheStore {
    private static final Logger LOGGER = LoggerFactory.getLogger("TS Modify");
    private static final String TEMP_SUFFIX = ".tmp";

    private final Path root;
    private final Path gitIgnore;
    private final int maxEntries;

    TranspileCacheStore(Path cacheDirectory, Path root, int maxEntries) {
        this.root = root.toAbsolutePath().normalize();
        this.gitIgnore = cacheDirectory.toAbsolutePath().normalize().resolve(".gitignore");
        this.maxEntries = Math.max(1, maxEntries);
    }

    Path root() {
        return root;
    }

    int maxEntries() {
        return maxEntries;
    }

    Optional<String> read(TranspileCacheKey key) {
        Path path = pathFor(key);
        try {
            if (!Files.isRegularFile(path)) {
                return Optional.empty();
            }
            String body = Files.readString(path, StandardCharsets.UTF_8);
            if (body.isBlank()) {
                discard(key, "cached body is empty");
                return Optional.empty();
            }
            touch(path);
            return Optional.of(body);
        } catch (MalformedInputException e) {
            discard(key, "cached body is not valid UTF-8");
            return Optional.empty();
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("Ignoring unreadable TS Modify transpile cache entry {}", path, e);
            return Optional.empty();
        }
    }

    void write(TranspileCacheKey key, String body) {
        Path path = pathFor(key);
        Path temporary = null;
        try {
            Files.createDirectories(path.getParent());
            ensureGitIgnore();
            temporary = Files.createTempFile(root, key.id(), TEMP_SUFFIX);
            Files.writeString(temporary, body, StandardCharsets.UTF_8);
            publish(temporary, path);
            temporary = null;
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("Failed to store TS Modify transpile cache entry {}", path, e);
        } finally {
            deleteQuietly(temporary);
        }
        evictIfOverCapacity();
    }

    void discard(TranspileCacheKey key, String reason) {
        LOGGER.debug("Discarding TS Modify transpile cache entry {}: {}", key.id(), reason);
        deleteQuietly(pathFor(key));
    }

    private void ensureGitIgnore() {
        try {
            if (Files.notExists(gitIgnore)) {
                Files.writeString(gitIgnore, "*\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            }
        } catch (FileAlreadyExistsException ignored) {
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("Failed to create TS Modify cache ignore file {}", gitIgnore, e);
        }
    }

    void clear() {
        try {
            if (!Files.isDirectory(root)) {
                return;
            }
            try (var paths = Files.walk(root)) {
                paths.filter(path -> !path.equals(gitIgnore) && Files.isRegularFile(path))
                        .forEach(TranspileCacheStore::deleteQuietly);
            }
        } catch (IOException e) {
            LOGGER.debug("Failed to clear TS Modify transpile cache directory {}", root, e);
        }
    }

    long entryCount() {
        List<Path> entries = listEntries();
        return entries.size();
    }

    private void publish(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            LOGGER.debug("Atomic move is unsupported for {}; falling back to a plain replace", target);
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void evictIfOverCapacity() {
        List<Path> entries = listEntries();
        int overflow = entries.size() - maxEntries;
        if (overflow <= 0) {
            return;
        }
        // Access refresh on read makes the oldest last-modified time the least recently used entry.
        entries.sort(Comparator.comparingLong(TranspileCacheStore::lastModifiedMillis));
        for (int index = 0; index < overflow && index < entries.size(); index++) {
            Path victim = entries.get(index);
            LOGGER.debug("Evicting TS Modify transpile cache entry {} (capacity {})", victim, maxEntries);
            deleteQuietly(victim);
        }
    }

    private List<Path> listEntries() {
        List<Path> entries = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return entries;
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (attributes.isRegularFile() && !file.equals(gitIgnore) && !file.getFileName().toString().endsWith(TEMP_SUFFIX)) {
                        entries.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exception) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOGGER.debug("Failed to enumerate TS Modify transpile cache directory {}", root, e);
        }
        return entries;
    }

    private Path pathFor(TranspileCacheKey key) {
        return root.resolve(key.shard()).resolve(key.fileName());
    }

    private static long lastModifiedMillis(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return Long.MAX_VALUE;
        }
    }

    private static void touch(Path path) {
        try {
            Files.setLastModifiedTime(path, FileTime.fromMillis(System.currentTimeMillis()));
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("Failed to refresh TS Modify transpile cache access time for {}", path, e);
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("Failed to delete TS Modify transpile cache file {}", path, e);
        }
    }
}
