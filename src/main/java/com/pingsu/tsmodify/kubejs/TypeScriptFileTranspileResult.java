package com.pingsu.tsmodify.kubejs;

import java.nio.file.Path;

public final class TypeScriptFileTranspileResult {
    private final Path sourcePath;
    private final Path outputPath;
    private final boolean transpiled;
    private final String sourceMap;

    TypeScriptFileTranspileResult(Path sourcePath, Path outputPath, boolean transpiled, String sourceMap) {
        this.sourcePath = sourcePath;
        this.outputPath = outputPath;
        this.transpiled = transpiled;
        this.sourceMap = sourceMap;
    }

    public Path sourcePath() {
        return sourcePath;
    }

    public Path outputPath() {
        return outputPath;
    }

    public boolean transpiled() {
        return transpiled;
    }

    public String sourceMap() {
        return sourceMap;
    }
}
