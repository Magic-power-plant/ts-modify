package com.pingsu.tsmodify.typescript.syntax;

import static com.pingsu.tsmodify.typescript.syntax.TypeScriptSourceScanner.isIdentifierPart;
import static com.pingsu.tsmodify.typescript.syntax.TypeScriptSourceScanner.maskCode;
import static com.pingsu.tsmodify.typescript.syntax.TypeScriptSourceScanner.matchesKeyword;
import static com.pingsu.tsmodify.typescript.syntax.TypeScriptSourceScanner.previousNonWhitespace;
import static com.pingsu.tsmodify.typescript.syntax.TypeScriptSourceScanner.readIdentifier;
import static com.pingsu.tsmodify.typescript.syntax.TypeScriptSourceScanner.skipWhitespace;

public final class TypeScriptAutomaticSemicolonGuard {
    private TypeScriptAutomaticSemicolonGuard() {
    }

    public static String guard(String source) {
        StringBuilder out = new StringBuilder(source);
        char[] mask = maskCode(source);
        int offset = 0;
        int lineStart = 0;
        int previousCodeLineStart = -1;
        int previousCodeLineEnd = -1;
        int previousCodeIndex = -1;
        int depthParen = 0;
        int depthBracket = 0;

        while (lineStart < mask.length) {
            int lineEnd = findLineEnd(mask, lineStart);
            int firstCode = firstNonWhitespace(mask, lineStart, lineEnd);
            if (firstCode < lineEnd) {
                if (isHazardStart(mask[firstCode])
                        && depthParen == 0
                        && depthBracket == 0
                        && canEndStatement(mask, previousCodeIndex)
                        && !isControlHead(mask, previousCodeLineStart, previousCodeLineEnd)) {
                    out.insert(firstCode + offset, ';');
                    offset++;
                }
            }

            for (int i = lineStart; i < lineEnd; i++) {
                if (mask[i] == '(') {
                    depthParen++;
                } else if (mask[i] == ')' && depthParen > 0) {
                    depthParen--;
                } else if (mask[i] == '[') {
                    depthBracket++;
                } else if (mask[i] == ']' && depthBracket > 0) {
                    depthBracket--;
                }
            }

            int lastCode = previousNonWhitespace(mask, lineEnd - 1);
            if (lastCode >= lineStart) {
                previousCodeLineStart = lineStart;
                previousCodeLineEnd = lineEnd;
                previousCodeIndex = lastCode;
            }
            lineStart = nextLineStart(mask, lineEnd);
        }

        return out.toString();
    }

    private static int findLineEnd(char[] mask, int lineStart) {
        int lineEnd = lineStart;
        while (lineEnd < mask.length && mask[lineEnd] != '\n' && mask[lineEnd] != '\r') {
            lineEnd++;
        }
        return lineEnd;
    }

    private static int nextLineStart(char[] mask, int lineEnd) {
        if (lineEnd < mask.length && mask[lineEnd] == '\r' && lineEnd + 1 < mask.length && mask[lineEnd + 1] == '\n') {
            return lineEnd + 2;
        }
        if (lineEnd < mask.length) {
            return lineEnd + 1;
        }
        return lineEnd;
    }

    private static int firstNonWhitespace(char[] mask, int start, int end) {
        int cursor = start;
        while (cursor < end && Character.isWhitespace(mask[cursor])) {
            cursor++;
        }
        return cursor;
    }

    private static boolean isHazardStart(char c) {
        return c == '(' || c == '[';
    }

    private static boolean canEndStatement(char[] mask, int index) {
        if (index < 0) {
            return false;
        }
        char c = mask[index];
        return isIdentifierPart(c) || c == ')' || c == ']' || c == '}';
    }

    private static boolean isControlHead(char[] mask, int start, int end) {
        if (start < 0 || end < 0) {
            return false;
        }
        int cursor = skipWhitespace(mask, start);
        if (cursor >= end) {
            return false;
        }
        int afterKeyword = readIdentifier(mask, cursor);
        int last = previousNonWhitespace(mask, end - 1);
        if (last < cursor) {
            return false;
        }
        if (matchesKeyword(mask, cursor, "if")
                || matchesKeyword(mask, cursor, "for")
                || matchesKeyword(mask, cursor, "while")
                || matchesKeyword(mask, cursor, "with")
                || matchesKeyword(mask, cursor, "switch")
                || matchesKeyword(mask, cursor, "catch")) {
            return mask[last] == ')';
        }
        if (matchesKeyword(mask, cursor, "else")
                || matchesKeyword(mask, cursor, "do")
                || matchesKeyword(mask, cursor, "try")
                || matchesKeyword(mask, cursor, "finally")) {
            return afterKeyword == last + 1;
        }
        return false;
    }
}
