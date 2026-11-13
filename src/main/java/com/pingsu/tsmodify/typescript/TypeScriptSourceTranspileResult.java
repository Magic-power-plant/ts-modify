package com.pingsu.tsmodify.typescript;

public final class TypeScriptSourceTranspileResult {
    private final String code;
    private final String sourceMap;
    private final boolean legacyFallback;
    private final boolean syntaxLoweringApplied;

    TypeScriptSourceTranspileResult(String code, String sourceMap, boolean legacyFallback) {
        this(code, sourceMap, legacyFallback, false);
    }

    TypeScriptSourceTranspileResult(
            String code,
            String sourceMap,
            boolean legacyFallback,
            boolean syntaxLoweringApplied) {
        this.code = code;
        this.sourceMap = sourceMap;
        this.legacyFallback = legacyFallback;
        this.syntaxLoweringApplied = syntaxLoweringApplied;
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

    /** Whether the requested JavaScript target lowering has been applied. */
    public boolean syntaxLoweringApplied() {
        return syntaxLoweringApplied;
    }
}
