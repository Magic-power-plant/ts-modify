package com.pingsu.tsmodify.typescript.syntax;

import static com.pingsu.tsmodify.typescript.syntax.TypeScriptSourceScanner.findMatching;
import static com.pingsu.tsmodify.typescript.syntax.TypeScriptSourceScanner.findStatementEnd;
import static com.pingsu.tsmodify.typescript.syntax.TypeScriptSourceScanner.isStatementBoundaryBefore;
import static com.pingsu.tsmodify.typescript.syntax.TypeScriptSourceScanner.maskCode;
import static com.pingsu.tsmodify.typescript.syntax.TypeScriptSourceScanner.matchesKeyword;
import static com.pingsu.tsmodify.typescript.syntax.TypeScriptSourceScanner.readIdentifier;
import static com.pingsu.tsmodify.typescript.syntax.TypeScriptSourceScanner.skipWhitespace;
import static com.pingsu.tsmodify.typescript.syntax.TypeScriptSourceScanner.splitTopLevel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TypeScriptCommonJsModuleTransformer {
    private static final Pattern SIDE_EFFECT_IMPORT = Pattern.compile(
            "^import\\s*(['\"])([^'\"]+)\\1\\s*;?\\s*$", Pattern.DOTALL);
    private static final Pattern BINDING_IMPORT = Pattern.compile(
            "^import\\s+(.+?)\\s+from\\s*(['\"])([^'\"]+)\\2\\s*;?\\s*$", Pattern.DOTALL);
    private static final Pattern EXPORT_FROM = Pattern.compile(
            "^export\\s*\\{(.*)}\\s+from\\s*(['\"])([^'\"]+)\\2\\s*;?\\s*$", Pattern.DOTALL);
    private static final Pattern EXPORT_STAR = Pattern.compile(
            "^export\\s*\\*\\s+from\\s*(['\"])([^'\"]+)\\1\\s*;?\\s*$", Pattern.DOTALL);
    private static final Pattern EXPORT_NAMESPACE_FROM = Pattern.compile(
            "^export\\s*\\*\\s+as\\s+([A-Za-z_$][\\w$]*)\\s+from\\s*(['\"])([^'\"]+)\\2\\s*;?\\s*$",
            Pattern.DOTALL);
    private static final Pattern EXPORT_LIST = Pattern.compile(
            "^export\\s*\\{(.*)}\\s*;?\\s*$", Pattern.DOTALL);

    private TypeScriptCommonJsModuleTransformer() {
    }

    public static String transform(String source) {
        char[] mask = maskCode(source);
        List<Replacement> replacements = new ArrayList<>();
        int temporaryIndex = 0;

        for (int i = 0; i < mask.length; i++) {
            if (!isStatementBoundaryBefore(mask, i)) {
                continue;
            }

            int start = skipWhitespace(mask, i);
            if (matchesKeyword(mask, start, "import")) {
                int end = findStatementEnd(mask, start);
                replacements.add(new Replacement(start, end, transformImport(source.substring(start, end), temporaryIndex++)));
                i = end - 1;
            } else if (matchesKeyword(mask, start, "export")) {
                int end = findStatementEnd(mask, start);
                temporaryIndex = transformExport(source, mask, replacements, start, end, temporaryIndex);
                i = end - 1;
            }
        }

        if (replacements.isEmpty()) {
            return source;
        }

        validateReplacements(source, replacements);
        StringBuilder out = new StringBuilder(source);
        replacements.stream()
                .sorted(Comparator.comparingInt(Replacement::start).reversed())
                .forEach(replacement -> out.replace(replacement.start(), replacement.end(), replacement.value()));
        return out.toString();
    }

    private static String transformImport(String statement, int temporaryIndex) {
        String trimmed = statement.trim();
        Matcher sideEffect = SIDE_EFFECT_IMPORT.matcher(trimmed);
        if (sideEffect.matches()) {
            return "require(\"" + escape(sideEffect.group(2)) + "\");\n";
        }

        Matcher binding = BINDING_IMPORT.matcher(trimmed);
        if (!binding.matches()) {
            return statement;
        }

        String clause = binding.group(1).trim();
        String specifier = escape(binding.group(3));
        String moduleVariable = "__tsmodify_module_" + temporaryIndex;
        List<String> lines = new ArrayList<>();

        int comma = findTopLevelComma(clause);
        if (comma >= 0) {
            String first = clause.substring(0, comma).trim();
            String second = clause.substring(comma + 1).trim();
            lines.add("var " + moduleVariable + " = require(\"" + specifier + "\");");
            if (!first.isEmpty()) {
                lines.add("var " + first + " = " + defaultAccess(moduleVariable) + ";");
            }
            appendImportBinding(lines, second, moduleVariable);
        } else if (clause.startsWith("{") || clause.startsWith("*")) {
            lines.add("var " + moduleVariable + " = require(\"" + specifier + "\");");
            appendImportBinding(lines, clause, moduleVariable);
        } else {
            lines.add("var " + clause + " = " + defaultAccess("require(\"" + specifier + "\")") + ";");
        }

        return String.join("\n", lines) + "\n";
    }

    private static void appendImportBinding(List<String> lines, String clause, String moduleVariable) {
        String trimmed = clause.trim();
        if (trimmed.startsWith("{")) {
            String body = trimmed.substring(1, trimmed.lastIndexOf('}')).trim();
            if (!body.isEmpty()) {
                for (String part : splitTopLevel(body, ',')) {
                    String[] names = splitAlias(part);
                    if (names != null) {
                        lines.add("var " + names[1] + " = " + moduleVariable + "." + names[0] + ";");
                    }
                }
            }
        } else if (trimmed.startsWith("*")) {
            Matcher matcher = Pattern.compile("^\\*\\s+as\\s+([A-Za-z_$][\\w$]*)$").matcher(trimmed);
            if (matcher.matches()) {
                lines.add("var " + matcher.group(1) + " = " + moduleVariable + ";");
            }
        }
    }

    private static int transformExport(
            String source,
            char[] mask,
            List<Replacement> replacements,
            int start,
            int end,
            int temporaryIndex) {
        String statement = source.substring(start, end);
        String trimmed = statement.trim();

        Matcher exportNamespaceFrom = EXPORT_NAMESPACE_FROM.matcher(trimmed);
        if (exportNamespaceFrom.matches()) {
            String exportName = exportNamespaceFrom.group(1);
            String specifier = escape(exportNamespaceFrom.group(3));
            String moduleVariable = "__tsmodify_module_" + temporaryIndex++;
            replacements.add(new Replacement(start, end,
                    "var " + moduleVariable + " = require(\"" + specifier + "\");\n"
                            + "exports." + exportName + " = " + moduleVariable + ";\n"));
            return temporaryIndex;
        }

        Matcher exportStar = EXPORT_STAR.matcher(trimmed);
        if (exportStar.matches()) {
            String specifier = escape(exportStar.group(2));
            replacements.add(new Replacement(start, end,
                    "(function(module) { for (var key in module) { if (Object.prototype.hasOwnProperty.call(module, key) && key !== \"default\" && key !== \"__esModule\") { exports[key] = module[key]; } } })(require(\""
                            + specifier + "\"));\n"));
            return temporaryIndex;
        }

        Matcher exportFrom = EXPORT_FROM.matcher(trimmed);
        if (exportFrom.matches()) {
            String body = exportFrom.group(1).trim();
            String specifier = escape(exportFrom.group(3));
            String moduleVariable = "__tsmodify_module_" + temporaryIndex++;
            StringBuilder replacement = new StringBuilder("var ")
                    .append(moduleVariable)
                    .append(" = require(\"")
                    .append(specifier)
                    .append("\");\n");
            appendExportAssignments(replacement, body, moduleVariable);
            replacements.add(new Replacement(start, end, replacement.toString()));
            return temporaryIndex;
        }

        Matcher exportList = EXPORT_LIST.matcher(trimmed);
        if (exportList.matches()) {
            StringBuilder replacement = new StringBuilder();
            appendExportAssignments(replacement, exportList.group(1).trim(), null);
            replacements.add(new Replacement(start, end, replacement.toString()));
            return temporaryIndex;
        }

        int cursor = skipWhitespace(mask, start + "export".length());
        if (matchesKeyword(mask, cursor, "default")) {
            replacements.add(new Replacement(start, end, transformDefaultExport(source, mask, cursor, end)));
            return temporaryIndex;
        }

        if (matchesKeyword(mask, cursor, "const")
                || matchesKeyword(mask, cursor, "let")
                || matchesKeyword(mask, cursor, "var")) {
            String declarationKind = readKeyword(mask, cursor);
            List<String> names = collectVariableNames(mask, cursor + declarationKind.length(), end);
            StringBuilder replacement = new StringBuilder(source.substring(cursor, end));
            appendExportAssignments(replacement, names);
            replacements.add(new Replacement(start, end, replacement.toString()));
        } else {
            int declarationStart = cursor;
            if (matchesKeyword(mask, cursor, "async")) {
                cursor = skipWhitespace(mask, cursor + "async".length());
            }
            if (matchesKeyword(mask, cursor, "function") || matchesKeyword(mask, cursor, "class")) {
                String keyword = matchesKeyword(mask, cursor, "function") ? "function" : "class";
                int nameStart = skipWhitespace(mask, cursor + keyword.length());
                if (keyword.equals("function") && nameStart < mask.length && mask[nameStart] == '*') {
                    nameStart = skipWhitespace(mask, nameStart + 1);
                }
                int nameEnd = readIdentifier(mask, nameStart);
                if (nameEnd > nameStart) {
                    String name = new String(mask, nameStart, nameEnd - nameStart);
                    StringBuilder replacement = new StringBuilder(source.substring(declarationStart, end));
                    appendExportAssignment(replacement, name, name);
                    replacements.add(new Replacement(start, end, replacement.toString()));
                } else {
                    throw unsupportedExport(trimmed);
                }
            } else {
                throw unsupportedExport(trimmed);
            }
        }

        return temporaryIndex;
    }

    private static String transformDefaultExport(
            String source,
            char[] mask,
            int defaultStart,
            int end) {
        int valueStart = skipWhitespace(mask, defaultStart + "default".length());
        int declarationStart = valueStart;
        if (matchesKeyword(mask, valueStart, "async")) {
            int afterAsync = skipWhitespace(mask, valueStart + "async".length());
            if (matchesKeyword(mask, afterAsync, "function")) {
                valueStart = afterAsync;
            }
        }
        if (matchesKeyword(mask, valueStart, "function") || matchesKeyword(mask, valueStart, "class")) {
            String keyword = matchesKeyword(mask, valueStart, "function") ? "function" : "class";
            int nameStart = skipWhitespace(mask, valueStart + keyword.length());
            if (keyword.equals("function") && nameStart < mask.length && mask[nameStart] == '*') {
                nameStart = skipWhitespace(mask, nameStart + 1);
            }
            int nameEnd = readIdentifier(mask, nameStart);
            if (nameEnd > nameStart) {
                String name = new String(mask, nameStart, nameEnd - nameStart);
                StringBuilder replacement = new StringBuilder(source.substring(declarationStart, end));
                appendExportAssignment(replacement, "default", name);
                return replacement.toString();
            } else {
                String expression = source.substring(declarationStart, end).trim();
                if (expression.endsWith(";")) {
                    expression = expression.substring(0, expression.length() - 1);
                }
                return "exports.default = " + expression + ";\n";
            }
        }

        String expression = source.substring(valueStart, end).trim();
        if (expression.endsWith(";")) {
            expression = expression.substring(0, expression.length() - 1);
        }
        return "exports.default = " + expression + ";\n";
    }

    private static void appendExportAssignments(StringBuilder out, String body, String moduleVariable) {
        if (body.isBlank()) {
            return;
        }
        for (String part : splitTopLevel(body, ',')) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] names = splitAlias(trimmed);
            if (names == null) {
                continue;
            }
            String source = moduleVariable == null ? names[0] : moduleVariable + "." + names[0];
            out.append("exports.").append(names[1]).append(" = ").append(source).append(";\n");
        }
    }

    private static void appendExportAssignments(StringBuilder out, List<String> names) {
        for (String name : names) {
            appendExportAssignment(out, name, name);
        }
    }

    private static void appendExportAssignment(StringBuilder out, String exportName, String localName) {
        if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') {
            out.append('\n');
        }
        out.append("exports.").append(exportName).append(" = ").append(localName).append(";\n");
    }

    private static List<String> collectVariableNames(char[] mask, int start, int end) {
        List<String> names = new ArrayList<>();
        int cursor = start;
        while (cursor < end) {
            cursor = skipWhitespace(mask, cursor);
            int nameStart = cursor;
            int nameEnd = readIdentifier(mask, nameStart);
            if (nameEnd > nameStart) {
                names.add(new String(mask, nameStart, nameEnd - nameStart));
                cursor = nameEnd;
            }
            cursor = findVariableDeclaratorBoundary(mask, cursor, end);
            if (cursor >= end || mask[cursor] != ',') {
                break;
            }
            cursor++;
        }
        return names;
    }

    private static int findVariableDeclaratorBoundary(char[] mask, int start, int end) {
        int depthParen = 0;
        int depthBracket = 0;
        int depthBrace = 0;
        for (int i = start; i < end; i++) {
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
        return end;
    }

    private static int findTopLevelComma(String value) {
        return TypeScriptSourceScanner.findTopLevel(value, ',');
    }

    private static String[] splitAlias(String value) {
        String normalized = maskBindingTrivia(value);
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.startsWith("type ")) {
            return null;
        }
        char[] binding = normalized.toCharArray();
        int sourceEnd = readIdentifier(binding, 0);
        if (sourceEnd == 0) {
            throw unsupportedBinding(value);
        }
        String sourceName = normalized.substring(0, sourceEnd);
        int cursor = skipWhitespace(binding, sourceEnd);
        if (cursor == binding.length) {
            return new String[]{sourceName, sourceName};
        }
        if (!matchesKeyword(binding, cursor, "as")) {
            throw unsupportedBinding(value);
        }
        cursor = skipWhitespace(binding, cursor + "as".length());
        int exportEnd = readIdentifier(binding, cursor);
        if (exportEnd == cursor || skipWhitespace(binding, exportEnd) != binding.length) {
            throw unsupportedBinding(value);
        }
        return new String[]{sourceName, normalized.substring(cursor, exportEnd)};
    }

    private static String maskBindingTrivia(String value) {
        char[] mask = maskCode(value);
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = mask[i];
            out.append(c == ' ' ? ' ' : value.charAt(i));
        }
        return out.toString().replaceAll("\\s+", " ").trim();
    }

    private static String readKeyword(char[] mask, int start) {
        int end = readIdentifier(mask, start);
        return new String(mask, start, end - start);
    }

    private static String defaultAccess(String moduleExpression) {
        return "(" + moduleExpression + " && Object.prototype.hasOwnProperty.call(" + moduleExpression
                + ", \"default\") ? " + moduleExpression + ".default : " + moduleExpression + ")";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static IllegalArgumentException unsupportedExport(String statement) {
        return new IllegalArgumentException("Unsupported TypeScript export statement: " + statement);
    }

    private static IllegalArgumentException unsupportedBinding(String binding) {
        return new IllegalArgumentException("Unsupported export binding '" + binding + "'");
    }

    private static void validateReplacements(String source, List<Replacement> replacements) {
        for (Replacement replacement : replacements) {
            if (replacement.start() < 0 || replacement.end() < replacement.start() || replacement.end() > source.length()) {
                throw new IllegalStateException("Invalid TypeScript module replacement range ["
                        + replacement.start() + ", " + replacement.end() + "] for source length " + source.length());
            }
        }

        for (int i = 0; i < replacements.size(); i++) {
            Replacement left = replacements.get(i);
            for (int j = i + 1; j < replacements.size(); j++) {
                Replacement right = replacements.get(j);
                if (rangesOverlap(left, right)
                        || (isInsertion(left) && isPointInside(right, left.start()))
                        || (isInsertion(right) && isPointInside(left, right.start()))) {
                    throw new IllegalStateException("Overlapping TypeScript module replacements: "
                            + left + " and " + right);
                }
            }
        }
    }

    private static boolean rangesOverlap(Replacement left, Replacement right) {
        if (isInsertion(left) && isInsertion(right)) {
            return left.start() == right.start();
        }
        if (isInsertion(left)) {
            return false;
        }
        if (isInsertion(right)) {
            return false;
        }
        return left.start() < right.end() && right.start() < left.end();
    }

    private static boolean isInsertion(Replacement replacement) {
        return replacement.start() == replacement.end();
    }

    private static boolean isPointInside(Replacement replacement, int point) {
        return !isInsertion(replacement) && point >= replacement.start() && point < replacement.end();
    }

    private record Replacement(int start, int end, String value) {
    }
}
