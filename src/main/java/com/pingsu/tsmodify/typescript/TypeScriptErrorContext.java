package com.pingsu.tsmodify.typescript;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Structured context attached to a TypeScript runtime or transpilation failure.
 */
public record TypeScriptErrorContext(
        String phase,
        String sourceName,
        String moduleId,
        String requesterId,
        int line,
        int column,
        String sourceExcerpt,
        List<String> moduleChain) {
    public static final int UNKNOWN_POSITION = -1;

    public TypeScriptErrorContext {
        phase = normalize(phase);
        sourceName = normalize(sourceName);
        moduleId = normalize(moduleId);
        requesterId = normalize(requesterId);
        sourceExcerpt = normalize(sourceExcerpt);
        moduleChain = moduleChain == null
                ? List.of()
                : moduleChain.stream().filter(Objects::nonNull).collect(Collectors.toUnmodifiableList());
        line = line < 1 ? UNKNOWN_POSITION : line;
        column = column < 1 ? UNKNOWN_POSITION : column;
    }

    public boolean hasLocation() {
        return line != UNKNOWN_POSITION;
    }

    private static String normalize(String value) {
        return value == null ? "" : value;
    }
}
