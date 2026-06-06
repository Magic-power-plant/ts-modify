package com.pingsu.tsmodify.kubejs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TypeScriptTranspiler {
    private static final Logger LOGGER = LoggerFactory.getLogger("TS Modify");

    private TypeScriptTranspiler() {
    }

    public static List<String> transpile(String fileName, List<String> sourceLines) {
        if (!isTypeScriptFile(fileName)) {
            return sourceLines;
        }

        String source = String.join("\n", sourceLines);
        String transformed = source;
        transformed = removeTypeOnlyDeclarations(transformed);
        transformed = transformEnums(transformed);
        transformed = stripExportStatements(transformed);
        transformed = stripTypeScriptSyntax(transformed);
        transformed = isolateFileScope(fileName, transformed);
        return Arrays.asList(transformed.split("\n", -1));
    }

    private static boolean isTypeScriptFile(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".ts") && !lower.endsWith(".d.ts");
    }

    private static String isolateFileScope(String fileName, String source) {
        List<IdentifierRename> renames = collectTopLevelRenames(fileName, source);
        if (renames.isEmpty()) {
            return source;
        }

        StringBuilder out = new StringBuilder(source);
        char[] mask = maskCode(source);
        lowerTopLevelLexicalDeclarations(out, mask);
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
                i = collectVariableRenames(fileName, mask, renames, i);
            } else if (matchesKeyword(mask, i, "function")) {
                int nameStart = skipWhitespace(mask, i + "function".length());
                int nameEnd = readIdentifier(mask, nameStart);
                addRename(fileName, renames, mask, nameStart, nameEnd);
            } else if (matchesKeyword(mask, i, "class")) {
                int nameStart = skipWhitespace(mask, i + "class".length());
                int nameEnd = readIdentifier(mask, nameStart);
                addRename(fileName, renames, mask, nameStart, nameEnd);
            }
        }

        return renames;
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

    private static void lowerTopLevelLexicalDeclarations(StringBuilder out, char[] mask) {
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

            if (matchesKeyword(mask, i, "const")) {
                out.replace(i, i + "const".length(), "var  ");
                i += "const".length();
            } else if (matchesKeyword(mask, i, "let")) {
                out.replace(i, i + "let".length(), "var");
                i += "let".length();
            }
        }
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

    private static String removeTypeOnlyDeclarations(String source) {
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
                i = end;
                continue;
            }

            if (matchesKeyword(mask, cursor, "interface")) {
                int end = findBlockOrStatementEnd(mask, cursor + "interface".length());
                blankRange(out, start, end);
                i = end;
                continue;
            }

            if (matchesKeyword(mask, cursor, "type")) {
                int end = findTypeAliasEnd(mask, cursor + "type".length());
                blankRange(out, start, end);
                i = end;
            }
        }

        return out.toString();
    }

    private static String transformEnums(String source) {
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
            i = end;
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

    private static String stripExportStatements(String source) {
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
                i = end;
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

    private static String stripTypeScriptSyntax(String source) {
        StringBuilder out = new StringBuilder(source);

        removeSimpleKeywords(out, "abstract", "readonly", "public", "private", "protected", "override");
        removeImplementsClauses(out);
        removeOptionalMarkers(out);
        removeNonNullAssertions(out);
        removeGenericParameters(out);
        removeTypeAnnotations(out);
        removeAsLikeCasts(out, "as");
        removeAsLikeCasts(out, "satisfies");
        lowerOptionalCalls(out);

        return out.toString();
    }

    private static void lowerOptionalCalls(StringBuilder out) {
        boolean changed;
        do {
            changed = false;
            String source = out.toString();
            char[] mask = maskCode(source);

            for (int i = 0; i + 2 < mask.length; i++) {
                if (mask[i] != '?' || mask[i + 1] != '.' || mask[i + 2] != '(') {
                    continue;
                }

                int calleeEnd = previousNonWhitespace(mask, i - 1);
                int calleeStart = findOptionalCallCalleeStart(mask, calleeEnd);
                int argsEnd = findMatching(mask, i + 2, '(', ')');
                if (calleeStart < 0 || calleeEnd < calleeStart || argsEnd < 0) {
                    continue;
                }

                String callee = source.substring(calleeStart, calleeEnd + 1);
                String args = source.substring(i + 2, argsEnd + 1);
                String replacement = "(" + callee + " == null ? undefined : " + callee + args + ")";
                out.replace(calleeStart, argsEnd + 1, replacement);
                changed = true;
                break;
            }
        } while (changed);
    }

    private static int findOptionalCallCalleeStart(char[] mask, int calleeEnd) {
        if (calleeEnd < 0) {
            return -1;
        }

        int i = calleeEnd;
        int depthParen = 0;
        int depthBracket = 0;
        while (i >= 0) {
            char c = mask[i];
            if (c == ')') {
                depthParen++;
            } else if (c == '(') {
                if (depthParen == 0) {
                    break;
                }
                depthParen--;
            } else if (c == ']') {
                depthBracket++;
            } else if (c == '[') {
                if (depthBracket == 0) {
                    break;
                }
                depthBracket--;
            } else if (depthParen == 0 && depthBracket == 0 && !isOptionalCallCalleePart(c)) {
                break;
            }
            i--;
        }

        return i + 1;
    }

    private static boolean isOptionalCallCalleePart(char c) {
        return isIdentifierPart(c) || c == '.' || c == '#';
    }

    private static void removeSimpleKeywords(StringBuilder out, String... keywords) {
        char[] mask = maskCode(out.toString());
        for (String keyword : keywords) {
            for (int i = 0; i < mask.length; i++) {
                if (matchesKeyword(mask, i, keyword) && isModifierContext(mask, i)) {
                    blankRange(out, i, i + keyword.length());
                }
            }
        }
    }

    private static boolean isModifierContext(char[] mask, int start) {
        int prev = previousNonWhitespace(mask, start - 1);
        return prev < 0 || mask[prev] == '{' || mask[prev] == '(' || mask[prev] == ',' || mask[prev] == ';' || mask[prev] == '\n';
    }

    private static void removeImplementsClauses(StringBuilder out) {
        char[] mask = maskCode(out.toString());
        for (int i = 0; i < mask.length; i++) {
            if (!matchesKeyword(mask, i, "implements")) {
                continue;
            }

            int end = i + "implements".length();
            int depthAngle = 0;
            int depthParen = 0;
            int depthBracket = 0;
            while (end < mask.length) {
                char c = mask[end];
                if (c == '<') {
                    depthAngle++;
                } else if (c == '>' && depthAngle > 0) {
                    depthAngle--;
                } else if (c == '(') {
                    depthParen++;
                } else if (c == ')' && depthParen > 0) {
                    depthParen--;
                } else if (c == '[') {
                    depthBracket++;
                } else if (c == ']' && depthBracket > 0) {
                    depthBracket--;
                } else if (c == '{' && depthAngle == 0 && depthParen == 0 && depthBracket == 0) {
                    break;
                }
                end++;
            }
            blankRange(out, i, end);
            i = end;
        }
    }

    private static void removeOptionalMarkers(StringBuilder out) {
        char[] mask = maskCode(out.toString());
        for (int i = 0; i < mask.length; i++) {
            if (mask[i] == '?' && nextNonWhitespace(mask, i + 1) < mask.length && mask[nextNonWhitespace(mask, i + 1)] == ':') {
                out.setCharAt(i, ' ');
            }
        }
    }

    private static void removeNonNullAssertions(StringBuilder out) {
        char[] mask = maskCode(out.toString());
        for (int i = 0; i < mask.length; i++) {
            if (mask[i] != '!') {
                continue;
            }

            int prev = previousNonWhitespace(mask, i - 1);
            int next = nextNonWhitespace(mask, i + 1);
            if (prev < 0 || next >= mask.length || mask[next] == '=') {
                continue;
            }

            if (isIdentifierPart(mask[prev]) || mask[prev] == ')' || mask[prev] == ']') {
                out.setCharAt(i, ' ');
            }
        }
    }

    private static void removeGenericParameters(StringBuilder out) {
        char[] mask = maskCode(out.toString());
        for (int i = 0; i < mask.length; i++) {
            if (matchesKeyword(mask, i, "function") || matchesKeyword(mask, i, "class")) {
                int cursor = skipWhitespace(mask, i + (matchesKeyword(mask, i, "function") ? "function".length() : "class".length()));
                cursor = readIdentifier(mask, cursor);
                int angle = skipWhitespace(mask, cursor);
                if (angle < mask.length && mask[angle] == '<') {
                    int end = findMatchingAngle(mask, angle);
                    int next = end < 0 ? -1 : skipWhitespace(mask, end + 1);
                    if (end > angle && next >= 0 && next < mask.length && (mask[next] == '(' || mask[next] == '{' || matchesKeyword(mask, next, "extends"))) {
                        blankRange(out, angle, end + 1);
                    }
                }
            } else if (mask[i] == '<' && isGenericCallCandidate(mask, i)) {
                int end = findMatchingAngle(mask, i);
                int next = end < 0 ? -1 : skipWhitespace(mask, end + 1);
                if (end > i && next < mask.length && mask[next] == '(' && looksLikeTypeList(mask, i + 1, end)) {
                    blankRange(out, i, end + 1);
                }
            }
        }
    }

    private static boolean isGenericCallCandidate(char[] mask, int angle) {
        int prev = previousNonWhitespace(mask, angle - 1);
        return prev >= 0 && (isIdentifierPart(mask[prev]) || mask[prev] == ')' || mask[prev] == ']');
    }

    private static boolean looksLikeTypeList(char[] mask, int start, int end) {
        boolean sawIdentifier = false;
        for (int i = start; i < end; i++) {
            char c = mask[i];
            if (isIdentifierPart(c)) {
                sawIdentifier = true;
                continue;
            }
            if (Character.isWhitespace(c) || c == ',' || c == '.' || c == '<' || c == '>' || c == '[' || c == ']' || c == '?' || c == ':' || c == '|' || c == '&') {
                continue;
            }
            return false;
        }
        return sawIdentifier;
    }

    private static void removeTypeAnnotations(StringBuilder out) {
        char[] mask = maskCode(out.toString());
        for (int i = 0; i < mask.length; i++) {
            if (mask[i] != ':') {
                continue;
            }

            if (!isTypeAnnotationColon(mask, i)) {
                continue;
            }

            int end = findTypeAnnotationEnd(mask, i + 1);
            if (end > i + 1) {
                blankRange(out, i, end);
            }
        }
    }

    private static boolean isTypeAnnotationColon(char[] mask, int colon) {
        int prev = previousNonWhitespace(mask, colon - 1);
        int next = nextNonWhitespace(mask, colon + 1);
        if (prev < 0 || next >= mask.length || !isTypeStart(mask[next])) {
            return false;
        }

        if (mask[prev] == ')') {
            return isFunctionReturnType(mask, colon);
        }

        if (isIdentifierPart(mask[prev]) || mask[prev] == ']' || mask[prev] == '}') {
            return isVariableType(mask, colon) || isParameterType(mask, colon) || isClassFieldType(mask, colon);
        }

        return false;
    }

    private static boolean isFunctionReturnType(char[] mask, int colon) {
        int open = findOpeningParen(mask, previousNonWhitespace(mask, colon - 1));
        if (open < 0) {
            return false;
        }

        int beforeOpen = previousNonWhitespace(mask, open - 1);
        if (beforeOpen < 0) {
            return false;
        }

        return isIdentifierPart(mask[beforeOpen]) || mask[beforeOpen] == ')' || mask[beforeOpen] == ']';
    }

    private static boolean isVariableType(char[] mask, int colon) {
        int start = previousStatementBoundary(mask, colon);
        for (int i = start; i < colon; i++) {
            if (matchesKeyword(mask, i, "let") || matchesKeyword(mask, i, "const") || matchesKeyword(mask, i, "var")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isParameterType(char[] mask, int colon) {
        int open = nearestOpeningParen(mask, colon);
        if (open < 0) {
            return false;
        }

        int beforeOpen = previousNonWhitespace(mask, open - 1);
        if (beforeOpen < 0) {
            return true;
        }

        if (matchesKeywordEndingAt(mask, beforeOpen, "if") || matchesKeywordEndingAt(mask, beforeOpen, "for") || matchesKeywordEndingAt(mask, beforeOpen, "while") || matchesKeywordEndingAt(mask, beforeOpen, "switch")) {
            return false;
        }

        int close = findMatching(mask, open, '(', ')');
        if (close < 0) {
            return false;
        }

        int after = skipWhitespace(mask, close + 1);
        return after < mask.length && (mask[after] == '{' || startsWith(mask, after, "=>") || mask[after] == ':' || mask[after] == ';' || mask[after] == ',');
    }

    private static boolean isClassFieldType(char[] mask, int colon) {
        int lineStart = previousLineStart(mask, colon);
        int cursor = skipWhitespace(mask, lineStart);
        return cursor < colon && (isIdentifierStart(mask[cursor]) || mask[cursor] == '#');
    }

    private static int findTypeAnnotationEnd(char[] mask, int start) {
        int i = skipWhitespace(mask, start);
        int firstTypeToken = i;
        int depthParen = 0;
        int depthBracket = 0;
        int depthBrace = 0;
        int depthAngle = 0;

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
            } else if (c == '{' && depthParen == 0 && depthBracket == 0 && depthBrace == 0 && depthAngle == 0 && i != firstTypeToken) {
                return i;
            } else if (c == '{') {
                depthBrace++;
            } else if (c == '}' && depthBrace > 0) {
                depthBrace--;
            } else if (c == '<') {
                depthAngle++;
            } else if (c == '>' && depthAngle > 0) {
                depthAngle--;
            } else if (depthParen == 0 && depthBracket == 0 && depthBrace == 0 && depthAngle == 0) {
                if (c == '=' || c == ',' || c == ';' || c == ')' || c == '{' || startsWith(mask, i, "=>")) {
                    return i;
                }
                if (c == '\n') {
                    return i;
                }
            }
            i++;
        }

        return i;
    }

    private static void removeAsLikeCasts(StringBuilder out, String keyword) {
        char[] mask = maskCode(out.toString());
        for (int i = 0; i < mask.length; i++) {
            if (!matchesKeyword(mask, i, keyword)) {
                continue;
            }

            int prev = previousNonWhitespace(mask, i - 1);
            int next = skipWhitespace(mask, i + keyword.length());
            if (prev < 0 || next >= mask.length || mask[prev] == '.' || !isTypeStart(mask[next])) {
                continue;
            }

            int end = findCastEnd(mask, next);
            blankRange(out, i, end);
            i = end;
        }
    }

    private static int findCastEnd(char[] mask, int start) {
        int i = start;
        int depthParen = 0;
        int depthBracket = 0;
        int depthBrace = 0;
        int depthAngle = 0;
        while (i < mask.length) {
            char c = mask[i];
            if (c == '(') {
                depthParen++;
            } else if (c == ')' && depthParen == 0) {
                return i;
            } else if (c == ')' && depthParen > 0) {
                depthParen--;
            } else if (c == '[') {
                depthBracket++;
            } else if (c == ']' && depthBracket == 0) {
                return i;
            } else if (c == ']' && depthBracket > 0) {
                depthBracket--;
            } else if (c == '{') {
                depthBrace++;
            } else if (c == '}' && depthBrace == 0) {
                return i;
            } else if (c == '}' && depthBrace > 0) {
                depthBrace--;
            } else if (c == '<') {
                depthAngle++;
            } else if (c == '>' && depthAngle > 0) {
                depthAngle--;
            } else if (depthParen == 0 && depthBracket == 0 && depthBrace == 0 && depthAngle == 0) {
                if (c == ',' || c == ';' || c == '\n' || startsWith(mask, i, "&&") || startsWith(mask, i, "||")) {
                    return i;
                }
            }
            i++;
        }
        return i;
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

    private static int findStatementEnd(char[] mask, int start) {
        int i = start;
        int depthParen = 0;
        int depthBracket = 0;
        int depthBrace = 0;
        int depthAngle = 0;
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
            } else if (c == '<') {
                depthAngle++;
            } else if (c == '>' && depthAngle > 0) {
                depthAngle--;
            } else if (depthParen == 0 && depthBracket == 0 && depthBrace == 0 && depthAngle == 0 && (c == ';' || c == '\n')) {
                return i + 1;
            }
            i++;
        }
        return i;
    }

    private static int findNextTopLevel(char[] mask, int start, char first, char second) {
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

    private static char[] maskCode(String source) {
        char[] mask = source.toCharArray();
        boolean inSingle = false;
        boolean inDouble = false;
        boolean inTemplate = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        boolean escaped = false;

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

            if (inSingle || inDouble || inTemplate) {
                boolean end = (!escaped && ((inSingle && c == '\'') || (inDouble && c == '"') || (inTemplate && c == '`')));
                if (c != '\n' && c != '\r') {
                    mask[i] = ' ';
                }
                escaped = !escaped && c == '\\';
                if (end) {
                    inSingle = false;
                    inDouble = false;
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
                mask[i] = ' ';
                inTemplate = true;
            }
        }

        return mask;
    }

    private static void blankRange(StringBuilder out, int start, int end) {
        for (int i = Math.max(0, start); i < end && i < out.length(); i++) {
            char c = out.charAt(i);
            if (c != '\n' && c != '\r') {
                out.setCharAt(i, ' ');
            }
        }
    }

    private static boolean isStatementBoundaryBefore(char[] mask, int index) {
        int prev = previousNonWhitespace(mask, index - 1);
        return prev < 0 || mask[prev] == ';' || mask[prev] == '{' || mask[prev] == '}' || mask[prev] == '\n';
    }

    private static int previousStatementBoundary(char[] mask, int index) {
        for (int i = index; i >= 0; i--) {
            char c = mask[i];
            if (c == ';' || c == '{' || c == '}' || c == '\n') {
                return i + 1;
            }
        }
        return 0;
    }

    private static int previousLineStart(char[] mask, int index) {
        for (int i = index; i >= 0; i--) {
            if (mask[i] == '\n') {
                return i + 1;
            }
        }
        return 0;
    }

    private static int skipWhitespace(char[] mask, int index) {
        while (index < mask.length && Character.isWhitespace(mask[index])) {
            index++;
        }
        return index;
    }

    private static int previousNonWhitespace(char[] mask, int index) {
        while (index >= 0 && Character.isWhitespace(mask[index])) {
            index--;
        }
        return index;
    }

    private static int nextNonWhitespace(char[] mask, int index) {
        while (index < mask.length && Character.isWhitespace(mask[index])) {
            index++;
        }
        return index;
    }

    private static boolean matchesKeyword(char[] mask, int index, String keyword) {
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

    private static boolean matchesKeywordEndingAt(char[] mask, int index, String keyword) {
        int start = index - keyword.length() + 1;
        return matchesKeyword(mask, start, keyword);
    }

    private static boolean startsWith(char[] mask, int index, String value) {
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

    private static int readIdentifier(char[] mask, int start) {
        int i = start;
        if (i < mask.length && isIdentifierStart(mask[i])) {
            i++;
            while (i < mask.length && isIdentifierPart(mask[i])) {
                i++;
            }
        }
        return i;
    }

    private static boolean isIdentifierStart(char c) {
        return c == '_' || c == '$' || Character.isLetter(c);
    }

    private static boolean isIdentifierPart(char c) {
        return c == '_' || c == '$' || Character.isLetterOrDigit(c);
    }

    private static boolean isTypeStart(char c) {
        return isIdentifierStart(c) || c == '{' || c == '[' || c == '(' || c == '\'' || c == '"' || c == '?' || c == '|';
    }

    private static int findMatching(char[] mask, int start, char open, char close) {
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

    private static int findOpeningParen(char[] mask, int close) {
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

    private static int nearestOpeningParen(char[] mask, int index) {
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

    private static int findMatchingAngle(char[] mask, int start) {
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

    private static List<String> splitTopLevel(String value, char delimiter) {
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

    private static int findTopLevel(String value, char target) {
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

    private static String stripLineComments(String value) {
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
