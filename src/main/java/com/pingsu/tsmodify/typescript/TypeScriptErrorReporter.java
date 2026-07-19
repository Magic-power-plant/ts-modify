package com.pingsu.tsmodify.typescript;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Creates actionable diagnostics while retaining the original exception as the cause.
 */
public final class TypeScriptErrorReporter {
    private static final Pattern LINE_COLUMN_PATTERN = Pattern.compile(
            "(?<!\\d)(\\d{1,6}):(\\d{1,6})(?!\\d)");
    private static final Pattern LINE_COLUMN_WORDS_PATTERN = Pattern.compile(
            "(?i)\\bline\\s*[=:]?\\s*(\\d+)(?:\\s*[,;:]\\s*|\\s+)"
                    + "(?:column|col)\\s*[=:]?\\s*(\\d+)");
    private static final int CONTEXT_RADIUS = 1;
    private static final int MAX_SOURCE_LINE_LENGTH = 180;

    private TypeScriptErrorReporter() {
    }

    public static TypeScriptTranspileException transpileFailure(
            String sourceName,
            String source,
            String phase,
            Throwable cause) {
        return failure(phase, sourceName, "", "", source, List.of(), cause);
    }

    public static TypeScriptTranspileException scriptPreloadFailure(
            String sourceName,
            String scriptType,
            List<String> sourceLines,
            Throwable cause) {
        String phase = "script preload";
        if (scriptType != null && !scriptType.isBlank()) {
            phase += " (" + scriptType + ")";
        }
        String source = sourceLines == null ? "" : String.join("\n", sourceLines);
        return failure(phase, sourceName, "", "", source, List.of(), cause);
    }

    public static TypeScriptTranspileException moduleExecutionFailure(
            String moduleId,
            String sourceName,
            String source,
            List<String> moduleChain,
            Throwable cause) {
        return failure("module execution", sourceName, moduleId, "", source, moduleChain, cause);
    }

    public static TypeScriptTranspileException moduleResolutionFailure(
            String domain,
            String requesterId,
            String specifier,
            Throwable cause) {
        String safeRequester = requesterId == null || requesterId.isBlank() ? "<unknown>" : requesterId;
        String safeSpecifier = specifier == null || specifier.isBlank() ? "<missing>" : specifier;
        String phase = "module resolution";
        if (domain != null && !domain.isBlank()) {
            phase += " (" + domain + ")";
        }
        return failure(phase, safeRequester, safeSpecifier, safeRequester, "", List.of(safeRequester), cause);
    }

    public static TypeScriptTranspileException projectFileFailure(Path sourcePath, Throwable cause) {
        String sourceName = sourcePath == null ? "" : sourcePath.toString();
        return failure("project file processing", sourceName, "", "", "", List.of(), cause);
    }

    public static TypeScriptTranspileException eventFailure(
            String sourceName,
            int line,
            Throwable cause) {
        return failureAt(
                "KubeJS event callback",
                sourceName,
                "",
                "",
                "",
                List.of(),
                new Position(line, TypeScriptErrorContext.UNKNOWN_POSITION),
                cause);
    }

    private static TypeScriptTranspileException failure(
            String phase,
            String sourceName,
            String moduleId,
            String requesterId,
            String source,
            List<String> moduleChain,
            Throwable cause) {
        return failureAt(
                phase,
                sourceName,
                moduleId,
                requesterId,
                source,
                moduleChain,
                findPosition(cause, sourceName),
                cause);
    }

    private static TypeScriptTranspileException failureAt(
            String phase,
            String sourceName,
            String moduleId,
            String requesterId,
            String source,
            List<String> moduleChain,
            Position position,
            Throwable cause) {
        String excerpt = sourceExcerpt(source, position.line(), position.column());
        TypeScriptErrorContext context = new TypeScriptErrorContext(
                phase,
                sourceName,
                moduleId,
                requesterId,
                position.line(),
                position.column(),
                excerpt,
                moduleChain);
        return new TypeScriptTranspileException(format(context, cause), cause, context);
    }

    private static String format(TypeScriptErrorContext context, Throwable cause) {
        StringBuilder message = new StringBuilder("TS Modify error during ")
                .append(context.phase().isBlank() ? "unknown phase" : context.phase());
        if (!context.sourceName().isBlank()) {
            message.append(" [source=").append(context.sourceName()).append(']');
        }
        if (!context.moduleId().isBlank()) {
            message.append(" [module=").append(context.moduleId()).append(']');
        }
        if (!context.requesterId().isBlank()) {
            message.append(" [requester=").append(context.requesterId()).append(']');
        }
        if (context.hasLocation()) {
            message.append(" at line ").append(context.line());
            if (context.column() != TypeScriptErrorContext.UNKNOWN_POSITION) {
                message.append(", column ").append(context.column());
            }
        }
        if (!context.moduleChain().isEmpty()) {
            message.append(" [module-chain=").append(String.join(" -> ", context.moduleChain())).append(']');
        }
        String detail = cause == null ? "unknown failure" : cause.getMessage();
        if (detail == null || detail.isBlank()) {
            detail = cause == null ? "unknown failure" : cause.getClass().getSimpleName();
        }
        message.append(": ").append(detail);
        if (!context.sourceExcerpt().isBlank()) {
            message.append(System.lineSeparator()).append(context.sourceExcerpt());
        }
        return message.toString();
    }

    private static Position findPosition(Throwable cause, String sourceName) {
        for (Throwable current = cause; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null) {
                Matcher words = LINE_COLUMN_WORDS_PATTERN.matcher(message);
                if (words.find()) {
                    return new Position(parseInt(words.group(1)), parseInt(words.group(2)));
                }
                Matcher pair = LINE_COLUMN_PATTERN.matcher(message);
                if (pair.find()) {
                    return new Position(parseInt(pair.group(1)), parseInt(pair.group(2)));
                }
            }
            if (sourceName != null && !sourceName.isBlank()) {
                for (StackTraceElement element : current.getStackTrace()) {
                    if (sourceName.equals(element.getFileName()) && element.getLineNumber() > 0) {
                        return new Position(element.getLineNumber(), TypeScriptErrorContext.UNKNOWN_POSITION);
                    }
                }
            }
        }
        return new Position(TypeScriptErrorContext.UNKNOWN_POSITION, TypeScriptErrorContext.UNKNOWN_POSITION);
    }

    private static String sourceExcerpt(String source, int line, int column) {
        if (source == null || source.isEmpty() || line == TypeScriptErrorContext.UNKNOWN_POSITION) {
            return "";
        }
        String[] lines = source.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        if (line > lines.length) {
            return "";
        }

        StringBuilder excerpt = new StringBuilder();
        int first = Math.max(1, line - CONTEXT_RADIUS);
        int last = Math.min(lines.length, line + CONTEXT_RADIUS);
        for (int current = first; current <= last; current++) {
            String text = lines[current - 1];
            if (text.length() > MAX_SOURCE_LINE_LENGTH) {
                text = text.substring(0, MAX_SOURCE_LINE_LENGTH) + "...";
            }
            excerpt.append(String.format("  %4d | %s", current, text));
            if (current == line && column != TypeScriptErrorContext.UNKNOWN_POSITION) {
                int markerColumn = Math.max(1, Math.min(column, text.length() + 1));
                excerpt.append(System.lineSeparator())
                        .append("       | ")
                        .append(" ".repeat(markerColumn - 1))
                        .append('^');
            }
            if (current < last) {
                excerpt.append(System.lineSeparator());
            }
        }
        return excerpt.toString();
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return TypeScriptErrorContext.UNKNOWN_POSITION;
        }
    }

    private record Position(int line, int column) {
    }
}
