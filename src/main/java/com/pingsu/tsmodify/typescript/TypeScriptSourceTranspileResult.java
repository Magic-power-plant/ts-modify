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

    /**
     * Whether {@code code} has already been lowered to {@code transformTarget} by the bridge.
     *
     * <p>The merged bridge call performs the syntax lowering together with the TypeScript strip, so
     * the caller must not run the lowering pass a second time.</p>
     */
    public boolean syntaxLoweringApplied() {
        return syntaxLoweringApplied;
    }
}
