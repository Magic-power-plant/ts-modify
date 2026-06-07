package com.pingsu.tsmodify.kubejs;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class TypeScriptTranspiler {
    private TypeScriptTranspiler() {
    }

    public static List<String> transpile(String fileName, List<String> sourceLines) {
        if (!isTypeScriptFile(fileName)) {
            return sourceLines;
        }

        String transformed = String.join("\n", sourceLines);
        transformed = TypeScriptDeclarationStripper.removeTypeOnlyDeclarations(transformed);
        transformed = TypeScriptEnumTransformer.transformEnums(transformed);
        transformed = TypeScriptDeclarationStripper.stripExportStatements(transformed);
        transformed = TypeScriptSyntaxStripper.strip(transformed);
        transformed = TypeScriptFileScopeIsolator.isolate(fileName, transformed);
        return Arrays.asList(transformed.split("\n", -1));
    }

    private static boolean isTypeScriptFile(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".ts") && !lower.endsWith(".d.ts");
    }
}
