package com.pingsu.tsmodify.kubejs;

public class TypeScriptTranspileException extends RuntimeException {
    public TypeScriptTranspileException(String message) {
        super(message);
    }

    public TypeScriptTranspileException(String message, Throwable cause) {
        super(message, cause);
    }
}
