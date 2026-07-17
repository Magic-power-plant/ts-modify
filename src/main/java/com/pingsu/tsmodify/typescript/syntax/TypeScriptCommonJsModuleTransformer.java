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
            "^export\\s*\\{(.+)}\\s+from\\s*(['\"])([^'\"]+)\\2\\s*;?\\s*$", Pattern.DOTALL);
    private static final Pattern EXPORT_STAR = Pattern.compile(
            "^export\\s*\\*\\s+from\\s*(['\"])([^'\"]+)\\1\\s*;?\\s*$", Pattern.DOTALL);
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
            lines.add("const " + moduleVariable + " = require(\"" + specifier + "\");");
            if (!first.isEmpty()) {
                lines.add("const " + first + " = " + defaultAccess(moduleVariable) + ";");
            }
            appendImportBinding(lines, second, moduleVariable);
        } else if (clause.startsWith("{") || clause.startsWith("*")) {
            lines.add("const " + moduleVariable + " = require(\"" + specifier + "\");");
            appendImportBinding(lines, clause, moduleVariable);
        } else {
            lines.add("const " + clause + " = " + defaultAccess("require(\"" + specifier + "\")") + ";");
        }

        return String.join("\n", lines) + "\n";
    }

    private static void appendImportBinding(List<String> lines, String clause, String moduleVariable) {
        String trimmed = clause.trim();
        if (trimmed.startsWith("{")) {
            String body = trimmed.substring(1, trimmed.lastIndexOf('}')).trim();
            if (!body.isEmpty()) {
                lines.add("const {" + importDestructuring(body) + "} = " + moduleVariable + ";");
            }
        } else if (trimmed.startsWith("*")) {
            Matcher matcher = Pattern.compile("^\\*\\s+as\\s+([A-Za-z_$][\\w$]*)$").matcher(trimmed);
            if (matcher.matches()) {
                lines.add("const " + matcher.group(1) + " = " + moduleVariable + ";");
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

        Matcher exportStar = EXPORT_STAR.matcher(trimmed);
        if (exportStar.matches()) {
            String specifier = escape(exportStar.group(2));
            replacements.add(new Replacement(start, end,
                    "(function(module) { for (var key in module) { if (key !== \"default\" && key !== \"__esModule\") { exports[key] = module[key]; } } })(require(\""
                            + specifier + "\"));\n"));
            return temporaryIndex;
        }

        Matcher exportFrom = EXPORT_FROM.matcher(trimmed);
        if (exportFrom.matches()) {
            String body = exportFrom.group(1).trim();
            String specifier = escape(exportFrom.group(3));
            String moduleVariable = "__tsmodify_module_" + temporaryIndex++;
            StringBuilder replacement = new StringBuilder("const ")
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
            transformDefaultExport(source, mask, replacements, start, cursor, end);
            return temporaryIndex;
        }

        if (matchesKeyword(mask, cursor, "const")
                || matchesKeyword(mask, cursor, "let")
                || matchesKeyword(mask, cursor, "var")) {
            String declarationKind = readKeyword(mask, cursor);
            List<String> names = collectVariableNames(mask, cursor + declarationKind.length(), end);
            replacements.add(new Replacement(start, cursor, ""));
            replacements.add(new Replacement(end, end, exportAssignments(names)));
        } else if (matchesKeyword(mask, cursor, "function") || matchesKeyword(mask, cursor, "class")) {
            String keyword = matchesKeyword(mask, cursor, "function") ? "function" : "class";
            int nameStart = skipWhitespace(mask, cursor + keyword.length());
            int nameEnd = readIdentifier(mask, nameStart);
            if (nameEnd > nameStart) {
                String name = new String(mask, nameStart, nameEnd - nameStart);
                replacements.add(new Replacement(start, cursor, ""));
                replacements.add(new Replacement(end, end, "\nexports." + name + " = " + name + ";\n"));
            }
        }

        return temporaryIndex;
    }

    private static void transformDefaultExport(
            String source,
            char[] mask,
            List<Replacement> replacements,
            int exportStart,
            int defaultStart,
            int end) {
        int valueStart = skipWhitespace(mask, defaultStart + "default".length());
        if (matchesKeyword(mask, valueStart, "function") || matchesKeyword(mask, valueStart, "class")) {
            String keyword = matchesKeyword(mask, valueStart, "function") ? "function" : "class";
            int nameStart = skipWhitespace(mask, valueStart + keyword.length());
            int nameEnd = readIdentifier(mask, nameStart);
            if (nameEnd > nameStart) {
                String name = new String(mask, nameStart, nameEnd - nameStart);
                replacements.add(new Replacement(exportStart, valueStart, ""));
                replacements.add(new Replacement(end, end, "\nexports.default = " + name + ";\n"));
            } else {
                replacements.add(new Replacement(exportStart, valueStart, "exports.default = "));
            }
            return;
        }

        String expression = source.substring(valueStart, end).trim();
        if (expression.endsWith(";")) {
            expression = expression.substring(0, expression.length() - 1);
        }
        replacements.add(new Replacement(exportStart, end, "exports.default = " + expression + ";\n"));
    }

    private static String importDestructuring(String body) {
        List<String> entries = new ArrayList<>();
        for (String part : splitTopLevel(body, ',')) {
            String specifier = normalizeSpecifier(part);
            if (!specifier.isEmpty()) {
                entries.add(specifier);
            }
        }
        return String.join(", ", entries);
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
            String source = moduleVariable == null ? names[0] : moduleVariable + "." + names[0];
            out.append("exports.").append(names[1]).append(" = ").append(source).append(";\n");
        }
    }

    private static String exportAssignments(List<String> names) {
        StringBuilder out = new StringBuilder();
        for (String name : names) {
            out.append("\nexports.").append(name).append(" = ").append(name).append(";");
        }
        return out.append('\n').toString();
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

    private static String normalizeSpecifier(String part) {
        String[] names = splitAlias(part.trim());
        if (names[0].equals(names[1])) {
            return names[0];
        }
        return names[0] + ": " + names[1];
    }

    private static String[] splitAlias(String value) {
        String normalized = value.replaceFirst("^type\\s+", "").trim();
        Matcher matcher = Pattern.compile("^([A-Za-z_$][\\w$]*|default)\\s+as\\s+([A-Za-z_$][\\w$]*)$").matcher(normalized);
        if (matcher.matches()) {
            return new String[]{matcher.group(1), matcher.group(2)};
        }
        return new String[]{normalized, normalized};
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

    private record Replacement(int start, int end, String value) {
    }
}
