package com.pingsu.tsmodify.typescript.syntax;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

final class TypeScriptSourceScanner {
    private TypeScriptSourceScanner() {
    }

    static char[] maskCode(String source) {
        char[] mask = source.toCharArray();
        Deque<Integer> templateExpressionDepths = new ArrayDeque<>();
        boolean inSingle = false;
        boolean inDouble = false;
        boolean inTemplate = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        boolean escaped = false;
        int templateBraceDepth = 0;

        for (int i = 0; i < mask.length; i++) {
            char c = mask[i];
            char next = i + 1 < mask.length ? mask[i + 1] : '\0';

            if (inLineComment) {
                if (c == '\n') {
                    inLineComment = false;
                } else {
                    mask[i] = ' ';
                }
                continue;
            }

            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    mask[i] = ' ';
                    mask[i + 1] = ' ';
                    i++;
                    inBlockComment = false;
                } else if (c != '\n' && c != '\r') {
                    mask[i] = ' ';
                }
                continue;
            }

            if (inSingle || inDouble) {
                boolean end = !escaped && ((inSingle && c == '\'') || (inDouble && c == '"'));
                if (c != '\n' && c != '\r') {
                    mask[i] = ' ';
                }
                escaped = !escaped && c == '\\';
                if (end) {
                    inSingle = false;
                    inDouble = false;
                    escaped = false;
                }
                continue;
            }

            if (inTemplate) {
                if (!escaped && c == '$' && next == '{') {
                    mask[i] = ' ';
                    templateBraceDepth++;
                    templateExpressionDepths.push(templateBraceDepth);
                    inTemplate = false;
                    i++;
                    continue;
                }

                boolean end = !escaped && c == '`';
                if (!end && c != '\n' && c != '\r') {
                    mask[i] = ' ';
                }
                escaped = !escaped && c == '\\';
                if (end) {
                    inTemplate = false;
                    escaped = false;
                }
                continue;
            }

            if (c == '/' && next == '/') {
                mask[i] = ' ';
                mask[i + 1] = ' ';
                i++;
                inLineComment = true;
            } else if (c == '/' && next == '*') {
                mask[i] = ' ';
                mask[i + 1] = ' ';
                i++;
                inBlockComment = true;
            } else if (c == '\'') {
                mask[i] = ' ';
                inSingle = true;
            } else if (c == '"') {
                mask[i] = ' ';
                inDouble = true;
            } else if (c == '`') {
                inTemplate = true;
                escaped = false;
            } else if (templateBraceDepth > 0 && c == '{') {
                templateBraceDepth++;
            } else if (templateBraceDepth > 0 && c == '}') {
                Integer expressionDepth = templateExpressionDepths.peek();
                if (expressionDepth != null && templateBraceDepth == expressionDepth) {
                    templateExpressionDepths.pop();
                    templateBraceDepth--;
                    inTemplate = true;
                    escaped = false;
                } else {
                    templateBraceDepth--;
                }
            }
        }

        return mask;
    }

    static void blankRange(StringBuilder out, int start, int end) {
        for (int i = Math.max(0, start); i < end && i < out.length(); i++) {
            char c = out.charAt(i);
            if (c != '\n' && c != '\r') {
                out.setCharAt(i, ' ');
            }
        }
    }

    static boolean isStatementBoundaryBefore(char[] mask, int index) {
        if (index <= 0) {
            return true;
        }

        for (int i = index - 1; i >= 0; i--) {
            char c = mask[i];
            if (c == '\n' || c == '\r') {
                return true;
            }
            if (!Character.isWhitespace(c)) {
                return c == ';' || c == '{' || c == '}';
            }
        }

        return true;
    }

    static int findEnclosingOpeningBrace(char[] mask, int index) {
        int depth = 0;
        for (int i = index - 1; i >= 0; i--) {
            if (mask[i] == '}') {
                depth++;
            } else if (mask[i] == '{') {
                if (depth == 0) {
                    return i;
                }
                depth--;
            }
        }
        return -1;
    }

    static boolean isClassBodyOpeningBrace(char[] mask, int brace) {
        int start = previousBlockStatementBoundary(mask, brace - 1);
        for (int i = start; i < brace; i++) {
            if (matchesKeyword(mask, i, "class")) {
                return true;
            }
        }
        return false;
    }

    private static int previousBlockStatementBoundary(char[] mask, int index) {
        for (int i = index; i >= 0; i--) {
            char c = mask[i];
            if (c == ';' || c == '{' || c == '}') {
                return i + 1;
            }
        }
        return 0;
    }

    static int previousStatementBoundary(char[] mask, int index) {
        for (int i = index; i >= 0; i--) {
            char c = mask[i];
            if (c == ';' || c == '{' || c == '}' || c == '\n') {
                return i + 1;
            }
        }
        return 0;
    }

    static int previousLineStart(char[] mask, int index) {
        for (int i = index; i >= 0; i--) {
            if (mask[i] == '\n') {
                return i + 1;
            }
        }
        return 0;
    }

    static int skipWhitespace(char[] mask, int index) {
        while (index < mask.length && Character.isWhitespace(mask[index])) {
            index++;
        }
        return index;
    }

    static int previousNonWhitespace(char[] mask, int index) {
        while (index >= 0 && Character.isWhitespace(mask[index])) {
            index--;
        }
        return index;
    }

    static int nextNonWhitespace(char[] mask, int index) {
        while (index < mask.length && Character.isWhitespace(mask[index])) {
            index++;
        }
        return index;
    }

    static boolean matchesKeyword(char[] mask, int index, String keyword) {
        if (index < 0 || index + keyword.length() > mask.length) {
            return false;
        }
        for (int i = 0; i < keyword.length(); i++) {
            if (mask[index + i] != keyword.charAt(i)) {
                return false;
            }
        }
        int before = index - 1;
        int after = index + keyword.length();
        return (before < 0 || !isIdentifierPart(mask[before])) && (after >= mask.length || !isIdentifierPart(mask[after]));
    }

    static boolean matchesKeywordEndingAt(char[] mask, int index, String keyword) {
        int start = index - keyword.length() + 1;
        return matchesKeyword(mask, start, keyword);
    }

    static boolean startsWith(char[] mask, int index, String value) {
        if (index < 0 || index + value.length() > mask.length) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (mask[index + i] != value.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    static int readIdentifier(char[] mask, int start) {
        int i = start;
        if (i < mask.length && isIdentifierStart(mask[i])) {
            i++;
            while (i < mask.length && isIdentifierPart(mask[i])) {
                i++;
            }
        }
        return i;
    }

    static boolean isIdentifierStart(char c) {
        return c == '_' || c == '$' || Character.isLetter(c);
    }

    static boolean isIdentifierPart(char c) {
        return c == '_' || c == '$' || Character.isLetterOrDigit(c);
    }

    static boolean isTypeStart(char c) {
        return isIdentifierStart(c) || c == '{' || c == '[' || c == '(' || c == '\'' || c == '"' || c == '?' || c == '|';
    }

    static int findMatching(char[] mask, int start, char open, char close) {
        int depth = 0;
        for (int i = start; i < mask.length; i++) {
            if (mask[i] == open) {
                depth++;
            } else if (mask[i] == close) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    static int findOpeningParen(char[] mask, int close) {
        int depth = 0;
        for (int i = close; i >= 0; i--) {
            if (mask[i] == ')') {
                depth++;
            } else if (mask[i] == '(') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    static int nearestOpeningParen(char[] mask, int index) {
        int depth = 0;
        for (int i = index; i >= 0; i--) {
            if (mask[i] == ')') {
                depth++;
            } else if (mask[i] == '(') {
                if (depth == 0) {
                    return i;
                }
                depth--;
            } else if (depth == 0 && (mask[i] == ';' || mask[i] == '{' || mask[i] == '}' || mask[i] == '\n')) {
                return -1;
            }
        }
        return -1;
    }

    static int findMatchingAngle(char[] mask, int start) {
        int depth = 0;
        for (int i = start; i < mask.length; i++) {
            if (mask[i] == '<') {
                depth++;
            } else if (mask[i] == '>') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            } else if (depth == 1 && (mask[i] == ';' || mask[i] == '\n' || mask[i] == '{' || mask[i] == '}')) {
                return -1;
            }
        }
        return -1;
    }

    /**
     * Finds the end of one statement that starts at {@code start}.
     *
     * <p>Only grouping characters that are unambiguous in the inputs this runs on are tracked:
     * parentheses, brackets, and braces. Angle brackets are deliberately <em>not</em> treated as
     * nesting. The module rewrite runs on post-SWC JavaScript, where {@code <} is the less-than
     * operator, and counting it as a TypeScript type-argument opener made an ordinary comparison
     * such as {@code input < 0} keep the nesting depth open until some later {@code >}. The
     * statement then swallowed the following declaration, whose {@code export} keyword was never
     * rewritten, which fails at runtime with "identifier is a reserved word: export".</p>
     *
     * <p>The TypeScript-source callers only use this for {@code import} statements and for
     * {@code export { ... }} / {@code export * ...} forms, none of which can contain a generic
     * parameter list, so dropping angle tracking does not lose any needed nesting.</p>
     */
    static int findStatementEnd(char[] mask, int start) {
        int i = start;
        int depthParen = 0;
        int depthBracket = 0;
        int depthBrace = 0;
        while (i < mask.length) {
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
            } else if (depthParen == 0 && depthBracket == 0 && depthBrace == 0 && (c == ';' || c == '\n')) {
                return i + 1;
            }
            i++;
        }
        return i;
    }

    static int findNextTopLevel(char[] mask, int start, char first, char second) {
        int depthParen = 0;
        int depthBracket = 0;
        int depthAngle = 0;
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
            } else if (c == '<') {
                depthAngle++;
            } else if (c == '>' && depthAngle > 0) {
                depthAngle--;
            } else if (depthParen == 0 && depthBracket == 0 && depthAngle == 0 && (c == first || c == second)) {
                return i;
            }
        }
        return -1;
    }

    static List<String> splitTopLevel(String value, char delimiter) {
        char[] mask = maskCode(value);
        List<String> parts = new ArrayList<>();
        int start = 0;
        int depthParen = 0;
        int depthBracket = 0;
        int depthBrace = 0;
        int depthAngle = 0;

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
            } else if (c == '<') {
                depthAngle++;
            } else if (c == '>' && depthAngle > 0) {
                depthAngle--;
            } else if (c == delimiter && depthParen == 0 && depthBracket == 0 && depthBrace == 0 && depthAngle == 0) {
                parts.add(value.substring(start, i));
                start = i + 1;
            }
        }

        parts.add(value.substring(start));
        return parts;
    }

    static int findTopLevel(String value, char target) {
        char[] mask = maskCode(value);
        int depthParen = 0;
        int depthBracket = 0;
        int depthBrace = 0;
        int depthAngle = 0;
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
            } else if (c == '<') {
                depthAngle++;
            } else if (c == '>' && depthAngle > 0) {
                depthAngle--;
            } else if (c == target && depthParen == 0 && depthBracket == 0 && depthBrace == 0 && depthAngle == 0) {
                return i;
            }
        }
        return -1;
    }

    static String stripLineComments(String value) {
        char[] mask = maskCode(value);
        int commentStart = -1;
        for (int i = 0; i + 1 < value.length(); i++) {
            if (value.charAt(i) == '/' && value.charAt(i + 1) == '/' && mask[i] == ' ' && mask[i + 1] == ' ') {
                commentStart = i;
                break;
            }
        }
        return commentStart < 0 ? value : value.substring(0, commentStart);
    }
}
