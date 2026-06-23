package com.pingsu.tsmodify.kubejs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class TypeScriptProjectTranspileResult {
    private final List<TypeScriptFileTranspileResult> files;
    private final List<TypeScriptTranspileException> failures;

    TypeScriptProjectTranspileResult(
            List<TypeScriptFileTranspileResult> files,
            List<TypeScriptTranspileException> failures) {
        this.files = Collections.unmodifiableList(new ArrayList<>(files));
        this.failures = Collections.unmodifiableList(new ArrayList<>(failures));
    }

    public List<TypeScriptFileTranspileResult> files() {
        return files;
    }

    public List<TypeScriptTranspileException> failures() {
        return failures;
    }

    public boolean successful() {
        return failures.isEmpty();
    }

    public long transpiledFileCount() {
        return files.stream().filter(TypeScriptFileTranspileResult::transpiled).count();
    }
}
