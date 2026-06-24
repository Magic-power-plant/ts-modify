package com.pingsu.tsmodify.typescript.syntax;

import static com.pingsu.tsmodify.typescript.syntax.TypeScriptSourceScanner.blankRange;
import static com.pingsu.tsmodify.typescript.syntax.TypeScriptSourceScanner.findEnclosingOpeningBrace;
import static com.pingsu.tsmodify.typescript.syntax.TypeScriptSourceScanner.findMatching;
import static com.pingsu.tsmodify.typescript.syntax.TypeScriptSourceScanner.findMatchingAngle;
import static com.pingsu.tsmodify.typescript.syntax.TypeScriptSourceScanner.findOpeningParen;
import static com.pingsu.tsmodify.typescript.syntax.TypeScriptSourceScanner.isClassBodyOpeningBrace;
import static com.pingsu.tsmodify.typescript.syntax.TypeScriptSourceScanner.isIdentifierPart;
import static com.pingsu.tsmodify.typescript.syntax.TypeScriptSourceScanner.isIdentifierStart;
import static com.pingsu.tsmodify.typescript.syntax.TypeScriptSourceScanner.isTypeStart;
import static com.pingsu.tsmodify.typescript.syntax.TypeScriptSourceScanner.maskCode;
import static com.pingsu.tsmodify.typescript.syntax.TypeScriptSourceScanner.matchesKeyword;
import static com.pingsu.tsmodify.typescript.syntax.TypeScriptSourceScanner.matchesKeywordEndingAt;
import static com.pingsu.tsmodify.typescript.syntax.TypeScriptSourceScanner.nearestOpeningParen;
import static com.pingsu.tsmodify.typescript.syntax.TypeScriptSourceScanner.nextNonWhitespace;
import static com.pingsu.tsmodify.typescript.syntax.TypeScriptSourceScanner.previousLineStart;
import static com.pingsu.tsmodify.typescript.syntax.TypeScriptSourceScanner.previousNonWhitespace;
import static com.pingsu.tsmodify.typescript.syntax.TypeScriptSourceScanner.previousStatementBoundary;
import static com.pingsu.tsmodify.typescript.syntax.TypeScriptSourceScanner.readIdentifier;
import static com.pingsu.tsmodify.typescript.syntax.TypeScriptSourceScanner.skipWhitespace;
import static com.pingsu.tsmodify.typescript.syntax.TypeScriptSourceScanner.startsWith;

public final class TypeScriptSyntaxStripper {
    private TypeScriptSyntaxStripper() {
    }

    public static String strip(String source) {
        StringBuilder out = new StringBuilder(source);

        removeSimpleKeywords(out, "abstract", "readonly", "public", "private", "protected", "override");
        removeImplementsClauses(out);
        removeOptionalMarkers(out);
        removeNonNullAssertions(out);
        removeGenericParameters(out);
        removeTypeAnnotations(out);
        removeAsLikeCasts(out, "as");
        removeAsLikeCasts(out, "satisfies");
        lowerOptionalChains(out);
        lowerArrowFunctions(out);

        return out.toString();
    }

    public static String stripPostEs2015Syntax(String source) {
        StringBuilder out = new StringBuilder(source);
        lowerOptionalChains(out);
        return out.toString();
    }

    private static void lowerArrowFunctions(StringBuilder out) {
        boolean changed;
        do {
            changed = false;
            String source = out.toString();
            char[] mask = maskCode(source);

            for (int i = mask.length - 2; i >= 0; i--) {
                if (mask[i] != '=' || mask[i + 1] != '>') {
                    continue;
                }

                ArrowParameters parameters = findArrowParameters(source, mask, i);
                int bodyStart = skipWhitespace(mask, i + 2);
                if (parameters == null || bodyStart >= mask.length) {
                    continue;
                }

                int bodyEnd;
                String replacement;
                if (mask[bodyStart] == '{') {
                    int endBrace = findMatching(mask, bodyStart, '{', '}');
                    if (endBrace < 0) {
                        continue;
                    }

                    bodyEnd = endBrace + 1;
                    replacement = "function(" + parameters.text + ") " + source.substring(bodyStart, bodyEnd);
                } else {
                    bodyEnd = findArrowExpressionEnd(mask, bodyStart);
                    if (bodyEnd <= bodyStart) {
                        continue;
                    }

                    String expression = source.substring(bodyStart, bodyEnd).trim();
                    if (expression.isEmpty()) {
                        continue;
                    }
                    replacement = "function(" + parameters.text + ") { return " + expression + "; }";
                }

                out.replace(parameters.start, bodyEnd, replacement);
                changed = true;
                break;
            }
        } while (changed);
    }

    private static ArrowParameters findArrowParameters(String source, char[] mask, int arrowStart) {
        int paramsEnd = previousNonWhitespace(mask, arrowStart - 1);
        if (paramsEnd < 0) {
            return null;
        }

        if (mask[paramsEnd] == ')') {
            int paramsStart = findOpeningParen(mask, paramsEnd);
            if (paramsStart < 0) {
                return null;
            }

            return new ArrowParameters(paramsStart, source.substring(paramsStart + 1, paramsEnd).trim());
        }

        int paramsStart = paramsEnd;
        while (paramsStart > 0 && isIdentifierPart(mask[paramsStart - 1])) {
            paramsStart--;
        }

        if (readIdentifier(mask, paramsStart) != paramsEnd + 1) {
            return null;
        }

        return new ArrowParameters(paramsStart, source.substring(paramsStart, paramsEnd + 1).trim());
    }

    private static int findArrowExpressionEnd(char[] mask, int start) {
        int i = start;
        int depthParen = 0;
        int depthBracket = 0;
        int depthBrace = 0;
        int ternaryDepth = 0;
        boolean inTemplate = false;
        boolean escaped = false;

        while (i < mask.length) {
            char c = mask[i];

            if (inTemplate) {
                if (!escaped && c == '`') {
                    inTemplate = false;
                } else if (!escaped && c == '$' && i + 1 < mask.length && mask[i + 1] == '{') {
                    depthBrace++;
                    i++;
                }
                escaped = !escaped && c == '\\';
                i++;
                continue;
            }

            if (c == '`') {
                inTemplate = true;
                escaped = false;
            } else if (c == '(') {
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
            } else if (depthParen == 0 && depthBracket == 0 && depthBrace == 0) {
                if (c == '?' && !startsWith(mask, i, "?.") && !startsWith(mask, i, "??")) {
                    ternaryDepth++;
                } else if (c == ':' && ternaryDepth > 0) {
                    ternaryDepth--;
                } else if (ternaryDepth == 0 && (c == ',' || c == ';')) {
                    return i;
                } else if (ternaryDepth == 0 && (c == '\n' || c == '\r') && !continuesArrowExpressionAfterLineBreak(mask, i)) {
                    return i;
                }
            }
            i++;
        }

        return i;
    }

    private static boolean continuesArrowExpressionAfterLineBreak(char[] mask, int lineBreak) {
        int next = nextNonWhitespace(mask, lineBreak + 1);
        if (next >= mask.length) {
            return false;
        }

        if (mask[next] == '.' || mask[next] == '[' || startsWith(mask, next, "?.")) {
            return true;
        }

        int prev = previousNonWhitespace(mask, lineBreak - 1);
        return prev >= 0 && isExpressionOperator(mask, prev);
    }

    private static boolean isExpressionOperator(char[] mask, int index) {
        char c = mask[index];
        if (c == '+' || c == '-' || c == '*' || c == '/' || c == '%' || c == '&' || c == '|' || c == '^'
                || c == '!' || c == '~' || c == '=' || c == '<' || c == '>' || c == '?' || c == ':') {
            return true;
        }
        return c == '.'
                || startsWith(mask, index - 1, "?.")
                || startsWith(mask, index - 1, "??")
                || startsWith(mask, index - 1, "&&")
                || startsWith(mask, index - 1, "||");
    }

    private static void lowerOptionalChains(StringBuilder out) {
        boolean changed;
        do {
            changed = false;
            String source = out.toString();
            char[] mask = maskCode(source);

            for (int i = 0; i + 1 < mask.length; i++) {
                if (!startsWith(mask, i, "?.")) {
                    continue;
                }

                OptionalChain chain = readOptionalChain(source, mask, i);
                if (chain == null) {
                    continue;
                }

                out.replace(chain.start, chain.end, chain.toExpression());
                changed = true;
                break;
            }
        } while (changed);
    }

    private static OptionalChain readOptionalChain(String source, char[] mask, int optionalStart) {
        int baseEnd = previousNonWhitespace(mask, optionalStart - 1);
        int start = findExpressionStart(mask, baseEnd);
        if (start < 0 || start > baseEnd) {
            return null;
        }

        String base = source.substring(start, optionalStart).trim();
        if (base.isEmpty()) {
            return null;
        }

        OptionalChain chain = new OptionalChain(start, base);
        int cursor = optionalStart;
        while (cursor < mask.length) {
            cursor = skipWhitespace(mask, cursor);
            if (startsWith(mask, cursor, "?.")) {
                cursor = readChainSegment(source, mask, cursor + 2, true, chain);
            } else if (mask[cursor] == '.') {
                cursor = readChainSegment(source, mask, cursor + 1, false, chain);
            } else if (mask[cursor] == '[' || mask[cursor] == '(') {
                cursor = readChainSegment(source, mask, cursor, false, chain);
            } else {
                break;
            }
            if (cursor < 0) {
                return null;
            }
        }

        chain.end = cursor;
        return chain.hasOptionalSegment() ? chain : null;
    }

    private static int readChainSegment(String source, char[] mask, int cursor, boolean optional, OptionalChain chain) {
        cursor = skipWhitespace(mask, cursor);
        if (cursor >= mask.length) {
            return -1;
        }

        char c = mask[cursor];
        if (c == '(') {
            int end = findMatching(mask, cursor, '(', ')');
            if (end < 0) {
                return -1;
            }
            chain.add(new ChainSegment(optional, source.substring(cursor, end + 1)));
            return end + 1;
        }
        if (c == '[') {
            int end = findMatching(mask, cursor, '[', ']');
            if (end < 0) {
                return -1;
            }
            chain.add(new ChainSegment(optional, source.substring(cursor, end + 1)));
            return end + 1;
        }

        int nameStart = cursor;
        int nameEnd = readIdentifier(mask, nameStart);
        if (nameEnd <= nameStart) {
            return -1;
        }
        chain.add(new ChainSegment(optional, "." + source.substring(nameStart, nameEnd)));
        return nameEnd;
    }

    private static int findExpressionStart(char[] mask, int expressionEnd) {
        if (expressionEnd < 0) {
            return -1;
        }

        int start = findPostfixExpressionStart(mask, expressionEnd);
        while (start > 0) {
            int dot = previousNonWhitespace(mask, start - 1);
            if (dot < 0 || mask[dot] != '.') {
                break;
            }

            int ownerEnd = previousNonWhitespace(mask, dot - 1);
            if (ownerEnd < 0) {
                break;
            }
            start = findPostfixExpressionStart(mask, ownerEnd);
        }
        return start;
    }

    private static int findPostfixExpressionStart(char[] mask, int expressionEnd) {
        int start = findPrimaryExpressionStart(mask, expressionEnd);
        while (start > 0) {
            int previous = previousNonWhitespace(mask, start - 1);
            if (previous < 0) {
                break;
            }

            if (mask[previous] == '.') {
                int ownerEnd = previousNonWhitespace(mask, previous - 1);
                if (ownerEnd < 0) {
                    break;
                }
                start = findPrimaryExpressionStart(mask, ownerEnd);
            } else {
                break;
            }
        }
        return start;
    }

    private static int findPrimaryExpressionStart(char[] mask, int expressionEnd) {
        char c = mask[expressionEnd];
        if (isIdentifierPart(c)) {
            int start = expressionEnd;
            while (start > 0 && isIdentifierPart(mask[start - 1])) {
                start--;
            }
            return start;
        }

        if (c == ')') {
            int open = findOpeningParen(mask, expressionEnd);
            if (open < 0) {
                return expressionEnd;
            }
            int calleeEnd = previousNonWhitespace(mask, open - 1);
            if (calleeEnd >= 0 && (isIdentifierPart(mask[calleeEnd]) || mask[calleeEnd] == ')' || mask[calleeEnd] == ']')) {
                return findExpressionStart(mask, calleeEnd);
            }
            return open;
        }

        if (c == ']') {
            int open = findOpeningBracket(mask, expressionEnd);
            if (open < 0) {
                return expressionEnd;
            }
            int ownerEnd = previousNonWhitespace(mask, open - 1);
            if (ownerEnd >= 0 && (isIdentifierPart(mask[ownerEnd]) || mask[ownerEnd] == ')' || mask[ownerEnd] == ']')) {
                return findExpressionStart(mask, ownerEnd);
            }
            return open;
        }

        return expressionEnd;
    }

    private static int findOpeningBracket(char[] mask, int close) {
        int depthParen = 0;
        for (int i = close; i >= 0; i--) {
            char c = mask[i];
            if (c == ']') {
                depthParen++;
            } else if (c == '[') {
                depthParen--;
                if (depthParen == 0) {
                    return i;
                }
            }
        }
        return -1;
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

        int typeEnd = findTypeAnnotationEnd(mask, colon + 1);
        int afterType = skipWhitespace(mask, typeEnd);
        if (afterType >= mask.length) {
            return false;
        }

        if (startsWith(mask, afterType, "=>")) {
            return true;
        }

        int beforeOpen = previousNonWhitespace(mask, open - 1);
        if (beforeOpen < 0) {
            return false;
        }

        if (!isIdentifierPart(mask[beforeOpen]) && mask[beforeOpen] != ')' && mask[beforeOpen] != ']') {
            return false;
        }

        return mask[afterType] == '{';
    }

    private static boolean isVariableType(char[] mask, int colon) {
        int start = previousStatementBoundary(mask, colon);
        boolean sawVariableKeyword = false;
        boolean sawAssignmentInDeclarator = false;
        int depthParen = 0;
        int depthBracket = 0;
        int depthBrace = 0;

        for (int i = start; i < colon; i++) {
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
            } else if (depthParen == 0 && depthBracket == 0 && depthBrace == 0) {
                if (matchesKeyword(mask, i, "let") || matchesKeyword(mask, i, "const") || matchesKeyword(mask, i, "var")) {
                    sawVariableKeyword = true;
                    sawAssignmentInDeclarator = false;
                } else if (c == ',') {
                    sawAssignmentInDeclarator = false;
                } else if (c == '=') {
                    sawAssignmentInDeclarator = true;
                }
            }
        }

        return sawVariableKeyword && !sawAssignmentInDeclarator && depthParen == 0 && depthBracket == 0 && depthBrace == 0;
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
        if (cursor >= colon || (!isIdentifierStart(mask[cursor]) && mask[cursor] != '#')) {
            return false;
        }

        int brace = findEnclosingOpeningBrace(mask, colon);
        return brace >= 0 && isClassBodyOpeningBrace(mask, brace);
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

    private static final class ArrowParameters {
        private final int start;
        private final String text;

        private ArrowParameters(int start, String text) {
            this.start = start;
            this.text = text;
        }
    }

    private static final class OptionalChain {
        private final int start;
        private final String base;
        private final java.util.List<ChainSegment> segments = new java.util.ArrayList<>();
        private int end;

        private OptionalChain(int start, String base) {
            this.start = start;
            this.base = base;
        }

        private void add(ChainSegment segment) {
            segments.add(segment);
        }

        private boolean hasOptionalSegment() {
            for (ChainSegment segment : segments) {
                if (segment.optional) {
                    return true;
                }
            }
            return false;
        }

        private String toExpression() {
            return toExpression(0, base);
        }

        private String toExpression(int index, String receiver) {
            if (index >= segments.size()) {
                return receiver;
            }

            ChainSegment segment = segments.get(index);
            String next = receiver + segment.text;
            String present = toExpression(index + 1, next);
            if (!segment.optional) {
                return present;
            }
            return "(" + receiver + " == null ? undefined : " + present + ")";
        }
    }

    private static final class ChainSegment {
        private final boolean optional;
        private final String text;

        private ChainSegment(boolean optional, String text) {
            this.optional = optional;
            this.text = text;
        }
    }
}
