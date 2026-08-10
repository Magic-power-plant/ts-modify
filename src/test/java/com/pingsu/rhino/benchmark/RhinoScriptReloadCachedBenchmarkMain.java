package com.pingsu.rhino.benchmark;

import com.pingsu.tsmodify.typescript.rhino.RhinoScriptCompilationCache;

import java.io.IOException;

/**
 * Optimized benchmark entry that uses the same direct production call as {@code ScriptFileMixin}.
 */
public final class RhinoScriptReloadCachedBenchmarkMain {
    private RhinoScriptReloadCachedBenchmarkMain() {
    }

    public static void main(String[] arguments) throws IOException {
        RhinoScriptReloadBenchmarkMain.runOptimized(
                RhinoScriptCompilationCache::evaluate,
                RhinoScriptCompilationCache.class);
    }
}
