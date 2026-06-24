package com.pingsu.tsmodify.typescript.syntax;

import static com.pingsu.tsmodify.typescript.syntax.TypeScriptSourceScanner.blankRange;
import static com.pingsu.tsmodify.typescript.syntax.TypeScriptSourceScanner.findMatching;
import static com.pingsu.tsmodify.typescript.syntax.TypeScriptSourceScanner.findMatchingAngle;
import static com.pingsu.tsmodify.typescript.syntax.TypeScriptSourceScanner.findNextTopLevel;
import static com.pingsu.tsmodify.typescript.syntax.TypeScriptSourceScanner.findStatementEnd;
import static com.pingsu.tsmodify.typescript.syntax.TypeScriptSourceScanner.isStatementBoundaryBefore;
import static com.pingsu.tsmodify.typescript.syntax.TypeScriptSourceScanner.maskCode;
import static com.pingsu.tsmodify.typescript.syntax.TypeScriptSourceScanner.matchesKeyword;
import static com.pingsu.tsmodify.typescript.syntax.TypeScriptSourceScanner.readIdentifier;
import static com.pingsu.tsmodify.typescript.syntax.TypeScriptSourceScanner.skipWhitespace;

public final class TypeScriptDeclarationStripper {
    private TypeScriptDeclarationStripper() {
    }

    public static String removeTypeOnlyDeclarations(String source) {
        StringBuilder out = new StringBuilder(source);
        char[] mask = maskCode(source);

        for (int i = 0; i < mask.length; i++) {
            if (!isStatementBoundaryBefore(mask, i)) {
                continue;
            }

            int start = skipWhitespace(mask, i);
            int cursor = start;

            if (matchesKeyword(mask, cursor, "export")) {
                cursor = skipWhitespace(mask, cursor + "export".length());
                if (matchesKeyword(mask, cursor, "default")) {
                    cursor = skipWhitespace(mask, cursor + "default".length());
                }
            }

            if (matchesKeyword(mask, cursor, "declare")) {
                int end = findDeclarationEnd(mask, cursor + "declare".length());
                blankRange(out, start, end);
                i = end - 1;
                continue;
            }

            if (matchesKeyword(mask, cursor, "interface")) {
                int end = findBlockOrStatementEnd(mask, cursor + "interface".length());
                blankRange(out, start, end);
                i = end - 1;
                continue;
            }

            if (isTypeAliasDeclaration(mask, cursor)) {
                int end = findTypeAliasEnd(mask, cursor + "type".length());
                blankRange(out, start, end);
                i = end - 1;
            }
        }

        return out.toString();
    }

    public static String stripExportStatements(String source) {
        StringBuilder out = new StringBuilder(source);
        char[] mask = maskCode(source);

        for (int i = 0; i < mask.length; i++) {
            if (!isStatementBoundaryBefore(mask, i)) {
                continue;
            }

            int start = skipWhitespace(mask, i);
            if (!matchesKeyword(mask, start, "export")) {
                continue;
            }

            int cursor = skipWhitespace(mask, start + "export".length());
            if (cursor < mask.length && mask[cursor] == '{') {
                int end = findStatementEnd(mask, cursor);
                blankRange(out, start, end);
                i = end - 1;
                continue;
            }

            if (cursor < mask.length && mask[cursor] == '*') {
                int end = findStatementEnd(mask, cursor);
                blankRange(out, start, end);
                i = end - 1;
                continue;
            }

            blankRange(out, start, cursor);
            if (matchesKeyword(mask, cursor, "default")) {
                int afterDefault = skipWhitespace(mask, cursor + "default".length());
                blankRange(out, cursor, afterDefault);
            }
        }

        return out.toString();
    }

    public static String stripImportStatements(String source) {
        StringBuilder out = new StringBuilder(source);
        char[] mask = maskCode(source);

        for (int i = 0; i < mask.length; i++) {
            if (!isStatementBoundaryBefore(mask, i)) {
                continue;
            }

            int start = skipWhitespace(mask, i);
            if (!matchesKeyword(mask, start, "import")) {
                continue;
            }

            int cursor = skipWhitespace(mask, start + "import".length());
            if (cursor < mask.length && (mask[cursor] == '(' || mask[cursor] == '.')) {
                continue;
            }

            int end = findStatementEnd(mask, cursor);
            blankRange(out, start, end);
            i = end - 1;
        }

        return out.toString();
    }

    private static int findDeclarationEnd(char[] mask, int start) {
        int next = skipWhitespace(mask, start);
        int brace = findNextTopLevel(mask, next, '{', ';');
        if (brace >= 0 && mask[brace] == '{') {
            int end = findMatching(mask, brace, '{', '}');
            return end < 0 ? mask.length : end + 1;
        }
        return findStatementEnd(mask, next);
    }

    private static int findBlockOrStatementEnd(char[] mask, int start) {
        int brace = findNextTopLevel(mask, start, '{', ';');
        if (brace >= 0 && mask[brace] == '{') {
            int end = findMatching(mask, brace, '{', '}');
            return end < 0 ? mask.length : end + 1;
        }
        return findStatementEnd(mask, start);
    }

    private static int findTypeAliasEnd(char[] mask, int start) {
        return findStatementEnd(mask, start);
    }

    private static boolean isTypeAliasDeclaration(char[] mask, int cursor) {
        if (!matchesKeyword(mask, cursor, "type")) {
            return false;
        }

        int nameStart = skipWhitespace(mask, cursor + "type".length());
        int nameEnd = readIdentifier(mask, nameStart);
        if (nameEnd <= nameStart) {
            return false;
        }

        int afterName = skipWhitespace(mask, nameEnd);
        if (afterName < mask.length && mask[afterName] == '<') {
            int end = findMatchingAngle(mask, afterName);
            if (end < 0) {
                return false;
            }
            afterName = skipWhitespace(mask, end + 1);
        }

        return afterName < mask.length && mask[afterName] == '=';
    }
}
