package com.pingsu.tsmodify.typescript.rhino;

import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.Script;
import dev.latvian.mods.rhino.Scriptable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Reuses compiled Rhino scripts while keeping every execution bound to its current context and scope.
 */
public final class RhinoScriptCompilationCache {
    private static final int DEFAULT_MAXIMUM_ENTRIES = 512;
    private static final ScriptCache CACHE = new BoundedScriptCache(maximumEntries());

    private RhinoScriptCompilationCache() {
    }

    /**
     * Evaluates a script with the same compile-then-execute behavior as {@link Context#evaluateString}.
     */
    public static Object evaluate(
            Context context,
            Scriptable scope,
            String source,
            String sourceName,
            int lineNumber,
            Object securityDomain) {
        Script script = compile(context, source, sourceName, lineNumber, securityDomain);
        return script == null ? null : script.exec(context, scope);
    }

    /**
     * Compiles a script while reusing bytecode for equivalent ordinary Rhino sources.
     *
     * <p>This is separate from {@link #evaluate(Context, Scriptable, String, String, int, Object)}
     * so a Mixin can preserve {@link Context#evaluateString}'s own execution path while replacing
     * only its compilation step.</p>
     */
    public static Script compile(
            Context context,
            String source,
            String sourceName,
            int lineNumber,
            Object securityDomain) {
        if (securityDomain != null) {
            return context.compileString(source, sourceName, lineNumber, securityDomain);
        }
        return CACHE.compile(context, source, sourceName, lineNumber);
    }

    static void clearForTests() {
        CACHE.clear();
    }

    static int entryCountForTests() {
        return CACHE.entryCount();
    }

    static boolean containsForTests(
            Context context,
            String source,
            String sourceName,
            int lineNumber) {
        return CACHE.contains(CacheKey.from(context, sourceName, lineNumber), source);
    }

    private static int maximumEntries() {
        int configured = Integer.getInteger("tsmodify.rhino.scriptCache.maxEntries", DEFAULT_MAXIMUM_ENTRIES);
        if (configured < 0) {
            throw new IllegalArgumentException("tsmodify.rhino.scriptCache.maxEntries must not be negative");
        }
        return configured;
    }

    private interface ScriptCache {
        Script compile(Context context, String source, String sourceName, int lineNumber);

        void clear();

        int entryCount();

        boolean contains(CacheKey key, String source);
    }

    /**
     * Keeps cache lookup and entry lifecycle separate from Context-bound script execution.
     */
    private abstract static class AbstractScriptCache implements ScriptCache {
        @Override
        public final Script compile(Context context, String source, String sourceName, int lineNumber) {
            CacheKey key = CacheKey.from(context, sourceName, lineNumber);
            CacheEntry entry = find(key);
            Script script = entry != null && Objects.equals(entry.source(), source) ? entry.script() : null;
            if (script == null) {
                script = context.compileString(source, sourceName, lineNumber, null);
                if (script != null) {
                    retain(key, new CacheEntry(source, script));
                }
            }
            return script;
        }

        protected abstract CacheEntry find(CacheKey key);

        protected abstract void retain(CacheKey key, CacheEntry entry);
    }

    private static final class BoundedScriptCache extends AbstractScriptCache {
        private final int maximumEntries;
        private final Map<CacheKey, CacheEntry> entries;

        private BoundedScriptCache(int maximumEntries) {
            this.maximumEntries = maximumEntries;
            entries = new LinkedHashMap<>(16, 0.75F, true);
        }

        @Override
        protected synchronized CacheEntry find(CacheKey key) {
            return entries.get(key);
        }

        @Override
        protected synchronized void retain(CacheKey key, CacheEntry entry) {
            if (maximumEntries == 0) {
                return;
            }
            entries.put(key, entry);
            while (entries.size() > maximumEntries) {
                CacheKey oldest = entries.keySet().iterator().next();
                entries.remove(oldest);
            }
        }

        @Override
        public synchronized void clear() {
            entries.clear();
        }

        @Override
        public synchronized int entryCount() {
            return entries.size();
        }

        @Override
        public synchronized boolean contains(CacheKey key, String source) {
            CacheEntry entry = entries.get(key);
            return entry != null && Objects.equals(entry.source(), source);
        }
    }

    private record CacheEntry(String source, Script script) {
    }

    private record CacheKey(
            String sourceName,
            int lineNumber,
            boolean strictMode,
            boolean generateObserverCount) {
        private static CacheKey from(Context context, String sourceName, int lineNumber) {
            return new CacheKey(
                    sourceName == null ? "unnamed script" : sourceName,
                    Math.max(lineNumber, 0),
                    context.isStrictMode(),
                    context.generateObserverCount);
        }
    }
}
