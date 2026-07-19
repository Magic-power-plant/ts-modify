package com.pingsu.tsmodify.typescript;

public class TypeScriptTranspileException extends RuntimeException {
    private final TypeScriptErrorContext context;

    public TypeScriptTranspileException(String message) {
        super(message);
        context = null;
    }

    public TypeScriptTranspileException(String message, Throwable cause) {
        super(message, cause);
        context = null;
    }

    public TypeScriptTranspileException(String message, Throwable cause, TypeScriptErrorContext context) {
        super(message, cause);
        this.context = context;
    }

    public TypeScriptErrorContext context() {
        return context;
    }
}
