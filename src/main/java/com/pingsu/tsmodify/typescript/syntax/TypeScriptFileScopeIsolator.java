package com.pingsu.tsmodify.typescript.syntax;

import static com.pingsu.tsmodify.typescript.syntax.TypeScriptSourceScanner.isIdentifierPart;
import static com.pingsu.tsmodify.typescript.syntax.TypeScriptSourceScanner.isIdentifierStart;
import static com.pingsu.tsmodify.typescript.syntax.TypeScriptSourceScanner.maskCode;
import static com.pingsu.tsmodify.typescript.syntax.TypeScriptSourceScanner.matchesKeyword;
import static com.pingsu.tsmodify.typescript.syntax.TypeScriptSourceScanner.nextNonWhitespace;
import static com.pingsu.tsmodify.typescript.syntax.TypeScriptSourceScanner.previousNonWhitespace;
import static com.pingsu.tsmodify.typescript.syntax.TypeScriptSourceScanner.previousStatementBoundary;
import static com.pingsu.tsmodify.typescript.syntax.TypeScriptSourceScanner.readIdentifier;
import static com.pingsu.tsmodify.typescript.syntax.TypeScriptSourceScanner.skipWhitespace;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TypeScriptFileScopeIsolator {
    private static final Logger LOGGER = LoggerFactory.getLogger("TS Modify");

    private TypeScriptFileScopeIsolator() {
    }

    public static String isolate(String fileName, String source) {
        List<IdentifierRename> renames = collectTopLevelRenames(fileName, source);
        if (renames.isEmpty()) {
            return source;
        }

        StringBuilder out = new StringBuilder(source);
        char[] mask = maskCode(source);
        rewriteIdentifierReferences(out, mask, renames);
        LOGGER.info("Transpiled KubeJS TypeScript file '{}' with {} isolated top-level name(s)", fileName, renames.size());
        return out.toString();
    }

    private static List<IdentifierRename> collectTopLevelRenames(String fileName, String source) {
        char[] mask = maskCode(source);
        List<IdentifierRename> renames = new ArrayList<>();
        int depthParen = 0;
        int depthBracket = 0;
        int depthBrace = 0;

        for (int i = 0; i < mask.length; i++) {
            char c = mask[i];
            if (c == '(') {
                depthParen++;
            } else if (c == ')' && depthParen > 0) {
                depthParen--;
            } else if (c == '[') {
                depthBracket++;
            } else if (c == ']' && depthBracket > 0) {
                depthBracket--;
            } else if (c == '{') {
                depthBrace++;
            } else if (c == '}' && depthBrace > 0) {
                depthBrace--;
            }

            if (depthParen != 0 || depthBracket != 0 || depthBrace != 0) {
                continue;
            }

            if (matchesKeyword(mask, i, "var") || matchesKeyword(mask, i, "let") || matchesKeyword(mask, i, "const")) {
                if (hasExportModifier(mask, i)) {
                    continue;
                }
                i = collectVariableRenames(fileName, mask, renames, i);
            } else if (matchesKeyword(mask, i, "function")) {
                if (hasExportModifier(mask, i)) {
                    continue;
                }
                int nameStart = skipWhitespace(mask, i + "function".length());
                int nameEnd = readIdentifier(mask, nameStart);
                addRename(fileName, renames, mask, nameStart, nameEnd);
            } else if (matchesKeyword(mask, i, "class")) {
                if (hasExportModifier(mask, i)) {
                    continue;
                }
                int nameStart = skipWhitespace(mask, i + "class".length());
                int nameEnd = readIdentifier(mask, nameStart);
                addRename(fileName, renames, mask, nameStart, nameEnd);
            }
        }

        return renames;
    }

    private static boolean hasExportModifier(char[] mask, int declarationKeywordStart) {
        int statementStart = skipWhitespace(mask, previousStatementBoundary(mask, declarationKeywordStart));
        if (!matchesKeyword(mask, statementStart, "export")) {
            return false;
        }

        int cursor = skipWhitespace(mask, statementStart + "export".length());
        if (matchesKeyword(mask, cursor, "default")) {
            cursor = skipWhitespace(mask, cursor + "default".length());
        }
        if (matchesKeyword(mask, cursor, "async")) {
            cursor = skipWhitespace(mask, cursor + "async".length());
        }
        return cursor == declarationKeywordStart;
    }

    private static int collectVariableRenames(String fileName, char[] mask, List<IdentifierRename> renames, int keywordStart) {
        int cursor = keywordStart;
        if (matchesKeyword(mask, cursor, "const")) {
            cursor += "const".length();
        } else if (matchesKeyword(mask, cursor, "let")) {
            cursor += "let".length();
        } else {
            cursor += "var".length();
        }

        while (cursor < mask.length) {
            cursor = skipWhitespace(mask, cursor);
            int nameStart = cursor;
            int nameEnd = readIdentifier(mask, nameStart);
            if (nameEnd > nameStart) {
                addRename(fileName, renames, mask, nameStart, nameEnd);
                cursor = nameEnd;
            }

            cursor = findVariableDeclaratorBoundary(mask, cursor);
            if (cursor >= mask.length || mask[cursor] != ',') {
                return cursor;
            }
            cursor++;
        }

        return cursor;
    }

    private static int findVariableDeclaratorBoundary(char[] mask, int start) {
        int depthParen = 0;
        int depthBracket = 0;
        int depthBrace = 0;
        for (int i = start; i < mask.length; i++) {
            char c = mask[i];
            if (c == '(') {
                depthParen++;
            } else if (c == ')' && depthParen > 0) {
                depthParen--;
            } else if (c == '[') {
                depthBracket++;
            } else if (c == ']' && depthBracket > 0) {
                depthBracket--;
            } else if (c == '{') {
                depthBrace++;
            } else if (c == '}' && depthBrace > 0) {
                depthBrace--;
            } else if (depthParen == 0 && depthBracket == 0 && depthBrace == 0 && (c == ',' || c == ';' || c == '\n')) {
                return i;
            }
        }
        return mask.length;
    }

    private static void addRename(String fileName, List<IdentifierRename> renames, char[] mask, int nameStart, int nameEnd) {
        if (nameEnd <= nameStart) {
            return;
        }

        String name = new String(mask, nameStart, nameEnd - nameStart);
        for (IdentifierRename rename : renames) {
            if (rename.original.equals(name)) {
                return;
            }
        }
        renames.add(new IdentifierRename(name, uniqueFileIdentifier(fileName, name)));
    }

    private static String uniqueFileIdentifier(String fileName, String name) {
        return "__tsmodify_" + Integer.toUnsignedString(fileName.hashCode(), 36) + "_" + name;
    }

    private static void rewriteIdentifierReferences(StringBuilder out, char[] mask, List<IdentifierRename> renames) {
        for (int i = mask.length - 1; i >= 0; i--) {
            if (!isIdentifierStart(mask[i])) {
                continue;
            }

            if (i > 0 && isIdentifierPart(mask[i - 1])) {
                continue;
            }

            int end = readIdentifier(mask, i);
            if (end <= i) {
                continue;
            }

            String identifier = new String(mask, i, end - i);
            IdentifierRename rename = findRename(renames, identifier);
            if (rename != null && shouldRewriteIdentifier(mask, i, end)) {
                out.replace(i, end, rename.replacement);
            }
            i--;
        }
    }

    private static IdentifierRename findRename(List<IdentifierRename> renames, String identifier) {
        for (IdentifierRename rename : renames) {
            if (rename.original.equals(identifier)) {
                return rename;
            }
        }
        return null;
    }

    private static boolean shouldRewriteIdentifier(char[] mask, int start, int end) {
        int prev = previousNonWhitespace(mask, start - 1);
        if (prev >= 0 && mask[prev] == '.') {
            return false;
        }

        int next = nextNonWhitespace(mask, end);
        if (next < mask.length && mask[next] == ':') {
            int before = previousNonWhitespace(mask, start - 1);
            return before >= 0 && before != '{' && before != ',';
        }

        return true;
    }

    private static final class IdentifierRename {
        private final String original;
        private final String replacement;

        private IdentifierRename(String original, String replacement) {
            this.original = original;
            this.replacement = replacement;
        }
    }
}
