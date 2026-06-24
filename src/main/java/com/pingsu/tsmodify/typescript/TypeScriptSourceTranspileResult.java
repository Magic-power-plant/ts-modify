package com.pingsu.tsmodify.typescript;

public final class TypeScriptSourceTranspileResult {
    private final String code;
    private final String sourceMap;
    private final boolean legacyFallback;

    TypeScriptSourceTranspileResult(String code, String sourceMap, boolean legacyFallback) {
        this.code = code;
        this.sourceMap = sourceMap;
        this.legacyFallback = legacyFallback;
    }

    public String code() {
        return code;
    }

    public String sourceMap() {
        return sourceMap;
    }

    public boolean legacyFallback() {
        return legacyFallback;
    }
}
