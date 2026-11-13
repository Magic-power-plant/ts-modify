package com.pingsu.tsmodify.typescript.cache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Objects;

/**
 * Content-addressed key for one transpiled KubeJS module body.
 *
 * <p>The key is the SHA-256 of a canonical descriptor built from:</p>
 * <ul>
 *   <li>the cache format version,</li>
 *   <li>the source media type extension ({@code .ts}, {@code .tsx}, {@code .mts}, {@code .cts}),
 *       because the compiler parses each media type differently,</li>
 *   <li>the {@link TranspilePipelineFingerprint} of the whole transpilation pipeline, and</li>
 *   <li>the SHA-256 of the exact source text that is transpiled.</li>
 * </ul>
 *
 * <p>Only the file extension, not the full path, participates in the key. The {@code preload} and
 * {@code loadSourceFromDisk} call sites name the same physical file differently (script-relative
 * module path versus module path), so hashing the full name would silently halve the hit rate.
 * {@code TranspileDiskCacheTest} pins the fact this relies on: for one extension the emitted body
 * does not depend on the source name.</p>
 *
 * <p>Identical content in different files therefore maps to one cache entry. That is safe because
 * the transpiler is a pure function of (extension, pipeline, source text).</p>
 */
public final class TranspileCacheKey {
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final String KEY_DESCRIPTOR_VERSION = "tsmodify-cache-key-v1";

    private final String id;
    private final String contentHash;
    private final String pipelineFingerprint;
    private final String extension;

    private TranspileCacheKey(String id, String contentHash, String pipelineFingerprint, String extension) {
        this.id = id;
        this.contentHash = contentHash;
        this.pipelineFingerprint = pipelineFingerprint;
        this.extension = extension;
    }

    public static TranspileCacheKey of(String sourceName, byte[] sourceBytes, String pipelineFingerprint) {
        Objects.requireNonNull(sourceName, "KubeJS TypeScript source name cannot be null");
        Objects.requireNonNull(sourceBytes, "KubeJS TypeScript source bytes cannot be null");
        Objects.requireNonNull(pipelineFingerprint, "Transpile pipeline fingerprint cannot be null");

        String contentHash = sha256Hex(sourceBytes);
        String extension = mediaTypeExtension(sourceName);
        String descriptor = KEY_DESCRIPTOR_VERSION
                + '\n' + extension
                + '\n' + pipelineFingerprint
                + '\n' + contentHash;
        String id = sha256Hex(descriptor.getBytes(StandardCharsets.UTF_8));
        return new TranspileCacheKey(id, contentHash, pipelineFingerprint, extension);
    }

    /**
     * Lowercased media type extension of a transpilable script name, or an empty string when the
     * name carries no recognized TypeScript extension.
     */
    public static String mediaTypeExtension(String sourceName) {
        String lower = sourceName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".tsx")) {
            return ".tsx";
        }
        if (lower.endsWith(".mts")) {
            return ".mts";
        }
        if (lower.endsWith(".cts")) {
            return ".cts";
        }
        if (lower.endsWith(".ts")) {
            return ".ts";
        }
        return "";
    }

    /** Full 64 character hex key. */
    public String id() {
        return id;
    }

    /** First two hex characters of {@link #id()}, used as the shard directory name. */
    public String shard() {
        return id.substring(0, 2);
    }

    /** Cache file name below the shard directory. */
    public String fileName() {
        return id + ".js";
    }

    /** SHA-256 of the source bytes alone; diagnostics only. */
    public String contentHash() {
        return contentHash;
    }

    public String pipelineFingerprint() {
        return pipelineFingerprint;
    }

    public String extension() {
        return extension;
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return toHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable in this JVM", e);
        }
    }

    private static String toHex(byte[] bytes) {
        char[] hex = new char[bytes.length * 2];
        for (int index = 0; index < bytes.length; index++) {
            int value = bytes[index] & 0xFF;
            hex[index * 2] = HEX[value >>> 4];
            hex[index * 2 + 1] = HEX[value & 0x0F];
        }
        return new String(hex);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TranspileCacheKey key && id.equals(key.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return id;
    }
}
