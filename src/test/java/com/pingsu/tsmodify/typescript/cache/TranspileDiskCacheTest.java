package com.pingsu.tsmodify.typescript.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TranspileDiskCacheTest {
    private static final String SOURCE = "const answer: number = 42;\n";
    private static final byte[] SOURCE_BYTES = bytes(SOURCE);
    private static final String BODY = "var answer = 42;\n";

    @TempDir
    Path tempDir;

    @Test
    void returnsStoredBodyOnHit() {
        TranspileDiskCache cache = newCache(16);

        cache.store("global.ts", SOURCE_BYTES, BODY);

        assertEquals(Optional.of(BODY), cache.lookup("global.ts", SOURCE_BYTES));
        assertEquals(1L, cache.entryCount());
    }

    @Test
    void missesWhenTheSourceContentChanges() {
        TranspileDiskCache cache = newCache(16);
        cache.store("global.ts", SOURCE_BYTES, BODY);

        assertTrue(cache.lookup("global.ts", bytes("const answer: number = 43;\n")).isEmpty());
        assertEquals(1L, cache.entryCount());
    }

    @Test
    void missesWhenThePipelineFingerprintChanges() {
        TranspileDiskCache cache = newCache(16);
        cache.store("global.ts", SOURCE_BYTES, BODY);

        TranspileCacheKey otherPipeline = TranspileCacheKey.of("global.ts", SOURCE_BYTES, "a-different-pipeline");
        assertNotEquals(cache.keyFor("global.ts", SOURCE_BYTES).id(), otherPipeline.id());
        assertFalse(Files.exists(entryPath(cache, otherPipeline)));

        TranspileCacheStore store = new TranspileCacheStore(cache.root().getParent(), cache.root(), 16);
        assertTrue(store.read(otherPipeline).isEmpty());
    }

    @Test
    void missesWhenTheSourceExtensionChanges() {
        TranspileDiskCache cache = newCache(16);
        cache.store("global.ts", SOURCE_BYTES, BODY);

        assertTrue(cache.lookup("global.tsx", SOURCE_BYTES).isEmpty());
        assertNotEquals(
                cache.keyFor("global.ts", SOURCE_BYTES).id(),
                cache.keyFor("global.tsx", SOURCE_BYTES).id());
    }

    @Test
    void ignoresTheDirectoryPrefixOfTheSourceName() {
        TranspileDiskCache cache = newCache(16);
        cache.store("server_scripts/machine/crafter.ts", SOURCE_BYTES, BODY);

        // preload and loadSourceFromDisk name the same physical file differently on purpose.
        assertEquals(Optional.of(BODY), cache.lookup("crafter.ts", SOURCE_BYTES));
        assertEquals(Optional.of(BODY), cache.lookup("shared_scripts/crafter.ts", SOURCE_BYTES));
    }

    @Test
    void treatsNonUtf8CachedBodyAsAMissAndRemovesIt() throws IOException {
        TranspileDiskCache cache = newCache(16);
        Path entry = entryPath(cache, cache.keyFor("global.ts", SOURCE_BYTES));
        Files.createDirectories(entry.getParent());
        Files.write(entry, new byte[]{(byte) 0xFF, (byte) 0xFE, 0x00, (byte) 0x80});

        assertTrue(cache.lookup("global.ts", SOURCE_BYTES).isEmpty());
        assertFalse(Files.exists(entry), "a corrupt entry must be discarded");
    }

    @Test
    void treatsEmptyCachedBodyAsAMissAndRemovesIt() throws IOException {
        TranspileDiskCache cache = newCache(16);
        Path entry = entryPath(cache, cache.keyFor("global.ts", SOURCE_BYTES));
        Files.createDirectories(entry.getParent());
        Files.write(entry, new byte[0]);

        assertTrue(cache.lookup("global.ts", SOURCE_BYTES).isEmpty());
        assertFalse(Files.exists(entry), "an empty entry must be discarded");
    }

    @Test
    void ignoresUnreadableCacheEntriesInsteadOfFailing() throws IOException {
        TranspileDiskCache cache = newCache(16);
        // A directory where the entry file belongs makes every read fail in a way the cache cannot
        // repair; the transpiler must still run and report a miss.
        Path entry = entryPath(cache, cache.keyFor("global.ts", SOURCE_BYTES));
        Files.createDirectories(entry);

        assertTrue(cache.lookup("global.ts", SOURCE_BYTES).isEmpty());
    }

    @Test
    void survivesConcurrentWritesAndReadsOfTheSameKey() throws Exception {
        TranspileDiskCache cache = newCache(64);
        int workers = 8;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger failures = new AtomicInteger();
        AtomicReference<String> observed = new AtomicReference<>();
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int index = 0; index < workers; index++) {
                futures.add(executor.submit(() -> {
                    try {
                        start.await();
                        for (int round = 0; round < 40; round++) {
                            cache.store("global.ts", SOURCE_BYTES, BODY);
                            cache.lookup("global.ts", SOURCE_BYTES).ifPresent(observed::set);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        failures.incrementAndGet();
                    } catch (RuntimeException e) {
                        failures.incrementAndGet();
                    }
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertEquals(0, failures.get());
        assertEquals(BODY, observed.get());
        assertEquals(1L, cache.entryCount(), "concurrent writers must not duplicate an entry");
        try (var entries = Files.list(cache.root())) {
            assertTrue(entries.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")),
                    "temporary files must not survive a publish");
        }
    }

    @Test
    void evictsTheLeastRecentlyUsedEntryOverCapacity() throws Exception {
        TranspileDiskCache cache = newCache(2);
        TranspileCacheKey firstKey = cache.keyFor("first.ts", bytes("first"));
        TranspileCacheKey secondKey = cache.keyFor("second.ts", bytes("second"));
        cache.store("first.ts", bytes("first"), "first body");
        cache.store("second.ts", bytes("second"), "second body");
        Path first = entryPath(cache, firstKey);
        Path second = entryPath(cache, secondKey);
        assertTrue(Files.exists(first));
        assertTrue(Files.exists(second));

        // Push "first" to the head of the LRU order by reading it.
        Files.setLastModifiedTime(first, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
        Thread.sleep(30L);
        assertTrue(cache.lookup("first.ts", bytes("first")).isPresent());
        Thread.sleep(30L);
        cache.store("third.ts", bytes("third"), "third body");

        assertTrue(cache.entryCount() <= 2L, "capacity must be enforced");
        assertTrue(Files.exists(first), "a recently read entry must survive eviction");
        assertTrue(Files.exists(entryPath(cache, cache.keyFor("third.ts", bytes("third")))));
    }

    @Test
    void disabledCacheIsANoOpThatAlwaysMisses() {
        TranspileDiskCache cache = TranspileDiskCache.disabled();

        cache.store("global.ts", SOURCE_BYTES, BODY);

        assertFalse(cache.enabled());
        assertTrue(cache.lookup("global.ts", SOURCE_BYTES).isEmpty());
        assertEquals(0L, cache.entryCount());
    }

    @Test
    void clearRemovesEveryEntry() {
        TranspileDiskCache cache = newCache(16);
        cache.store("a.ts", bytes("a"), "a body");
        cache.store("b.ts", bytes("b"), "b body");

        cache.clear();

        assertEquals(0L, cache.entryCount());
    }

    @Test
    void usesTheDocumentedShardedLayout() throws IOException {
        TranspileDiskCache cache = newCache(16);
        Path directory = tempDir.resolve("kubejs").resolve(TranspileDiskCache.CACHE_DIRECTORY_NAME);
        assertFalse(Files.exists(directory));
        cache.store("global.ts", SOURCE_BYTES, BODY);

        assertEquals("*\n", Files.readString(directory.resolve(".gitignore")));
        TranspileCacheKey key = cache.keyFor("global.ts", SOURCE_BYTES);
        assertEquals(64, key.id().length());
        assertEquals(key.id().substring(0, 2), key.shard());
        assertEquals(key.id() + ".js", key.fileName());
        assertTrue(Files.isRegularFile(
                tempDir.resolve("kubejs").resolve(TranspileDiskCache.CACHE_DIRECTORY_NAME)
                        .resolve(TranspileDiskCache.CACHE_FORMAT_DIRECTORY)
                        .resolve(key.shard()).resolve(key.fileName())));
    }

    @Test
    void keepsIgnoreRulesInsideAnExplicitRootAcrossWritesEvictionAndClear() throws IOException {
        Path directory = tempDir.resolve("custom-cache");
        TranspileDiskCache cache = TranspileDiskCache.create(directory, 1);
        Path gitIgnore = directory.resolve(".gitignore");
        cache.store("first.ts", bytes("first"), BODY);
        assertEquals("*\n", Files.readString(gitIgnore));
        assertFalse(Files.exists(tempDir.resolve(".gitignore")));
        Files.writeString(gitIgnore, "custom-rule\n");

        cache.store("second.ts", bytes("second"), BODY);
        assertEquals(1L, cache.entryCount());
        assertEquals("custom-rule\n", Files.readString(gitIgnore));
        cache.clear();
        assertEquals(0L, cache.entryCount());
        assertEquals("custom-rule\n", Files.readString(gitIgnore));
    }

    private Path entryPath(TranspileDiskCache cache, TranspileCacheKey key) {
        return cache.root().resolve(key.shard()).resolve(key.fileName());
    }

    private TranspileDiskCache newCache(int maxEntries) {
        return TranspileDiskCache.createDefault(tempDir.resolve("kubejs"), maxEntries);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
