package com.pingsu.tsmodify.kubejs;

import static com.pingsu.tsmodify.kubejs.TypeScriptSourceScanner.findMatching;
import static com.pingsu.tsmodify.kubejs.TypeScriptSourceScanner.findTopLevel;
import static com.pingsu.tsmodify.kubejs.TypeScriptSourceScanner.isStatementBoundaryBefore;
import static com.pingsu.tsmodify.kubejs.TypeScriptSourceScanner.maskCode;
import static com.pingsu.tsmodify.kubejs.TypeScriptSourceScanner.matchesKeyword;
import static com.pingsu.tsmodify.kubejs.TypeScriptSourceScanner.readIdentifier;
import static com.pingsu.tsmodify.kubejs.TypeScriptSourceScanner.skipWhitespace;
import static com.pingsu.tsmodify.kubejs.TypeScriptSourceScanner.splitTopLevel;
import static com.pingsu.tsmodify.kubejs.TypeScriptSourceScanner.stripLineComments;

import java.util.ArrayList;
import java.util.List;

final class TypeScriptEnumTransformer {
    private TypeScriptEnumTransformer() {
    }

    static String transformEnums(String source) {
        StringBuilder out = new StringBuilder(source);
        char[] mask = maskCode(source);
        int delta = 0;

        for (int i = 0; i < mask.length; i++) {
            if (!isStatementBoundaryBefore(mask, i)) {
                continue;
            }

            int start = skipWhitespace(mask, i);
            int cursor = start;

            if (matchesKeyword(mask, cursor, "export")) {
                cursor = skipWhitespace(mask, cursor + "export".length());
            }

            if (matchesKeyword(mask, cursor, "const")) {
                int afterConst = skipWhitespace(mask, cursor + "const".length());
                if (matchesKeyword(mask, afterConst, "enum")) {
                    cursor = afterConst;
                }
            }

            if (!matchesKeyword(mask, cursor, "enum")) {
                continue;
            }

            int nameStart = skipWhitespace(mask, cursor + "enum".length());
            int nameEnd = readIdentifier(mask, nameStart);
            if (nameEnd <= nameStart) {
                continue;
            }

            int brace = skipWhitespace(mask, nameEnd);
            if (brace >= mask.length || mask[brace] != '{') {
                continue;
            }

            int endBrace = findMatching(mask, brace, '{', '}');
            if (endBrace < 0) {
                continue;
            }

            int end = endBrace + 1;
            int next = skipWhitespace(mask, end);
            if (next < mask.length && mask[next] == ';') {
                end = next + 1;
            }

            String enumName = source.substring(nameStart, nameEnd);
            String body = source.substring(brace + 1, endBrace);
            String replacement = buildEnumObject(enumName, body);
            out.replace(start + delta, end + delta, replacement);
            delta += replacement.length() - (end - start);
            i = end - 1;
        }

        return out.toString();
    }

    private static String buildEnumObject(String enumName, String body) {
        List<String> entries = splitTopLevel(body, ',');
        List<String> assignments = new ArrayList<>();
        int nextNumeric = 0;

        for (String entry : entries) {
            String trimmed = stripLineComments(entry).trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            int eq = findTopLevel(trimmed, '=');
            String rawName = eq < 0 ? trimmed : trimmed.substring(0, eq).trim();
            String value = eq < 0 ? Integer.toString(nextNumeric) : trimmed.substring(eq + 1).trim();
            String property = enumPropertyName(rawName);
            assignments.add(property + ": " + value);

            Integer numeric = parseIntegerLiteral(value);
            if (numeric != null) {
                nextNumeric = numeric + 1;
            } else {
                nextNumeric++;
            }
        }

        return "var " + enumName + " = Object.freeze({ " + String.join(", ", assignments) + " });";
    }

    private static String enumPropertyName(String rawName) {
        String name = rawName.trim();
        if ((name.startsWith("'") && name.endsWith("'")) || (name.startsWith("\"") && name.endsWith("\""))) {
            return name;
        }
        return name;
    }

    private static Integer parseIntegerLiteral(String value) {
        String trimmed = value.trim().replace("_", "");
        try {
            if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
                return Integer.parseInt(trimmed.substring(2), 16);
            }
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
