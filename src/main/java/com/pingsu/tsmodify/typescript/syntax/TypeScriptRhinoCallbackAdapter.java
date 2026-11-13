package com.pingsu.tsmodify.typescript.syntax;

import com.pingsu.transpiler.syntax.SyntaxNode;
import com.pingsu.tsmodify.typescript.TranslanguageCompiler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.StringJoiner;

/** Makes lowered arrows usable as Java functional interfaces without losing their bound receiver. */
public final class TypeScriptRhinoCallbackAdapter {
    private TypeScriptRhinoCallbackAdapter() {
    }

    public static String adapt(String fileName, String source) {
        if (!source.contains("$tl$callable$") || !source.contains("bind")) {
            return source;
        }

        // Rhino accepts NativeFunction/ArrowFunction for Java interfaces, but not BoundFunction.
        // Inspect syntax rather than searching balanced text: callback bodies can contain regexes,
        // strings, comments and nested callbacks. Insertions also preserve the original line count.
        List<Insertion> insertions = new ArrayList<>();
        TranslanguageCompiler.parseJavaScript(fileName, source).walk(node -> {
            SyntaxNode function = boundArrowFunction(node, source);
            if (function != null) {
                int arity = function.children().stream()
                        .filter(child -> child.kind().equals("parameters"))
                        .findFirst().orElseThrow().children().size();
                insertions.add(new Insertion(node.range().start(), wrapper(arity) + "("));
                insertions.add(new Insertion(node.range().end(), ")"));
            }
            return true;
        });

        StringBuilder result = new StringBuilder(source);
        insertions.sort(Comparator.comparingInt(Insertion::offset).reversed());
        for (Insertion insertion : insertions) {
            result.insert(insertion.offset(), insertion.text());
        }
        return result.toString();
    }

    private static SyntaxNode boundArrowFunction(SyntaxNode node, String source) {
        if (!node.kind().equals("argumentsExpression") || node.children().size() != 2) {
            return null;
        }
        SyntaxNode member = node.children().get(0);
        SyntaxNode arguments = node.children().get(1);
        if (!member.kind().equals("memberExpression") || member.children().size() != 2
                || !text(member.children().get(1), source).equals("bind")
                || !arguments.kind().equals("arguments") || arguments.children().size() != 1
                || !arguments.children().get(0).kind().equals("thisExpression")) {
            return null;
        }

        return generatedFunctionValue(member.children().get(0), source);
    }

    private static SyntaxNode generatedFunctionValue(SyntaxNode expression, String source) {
        SyntaxNode value = unwrapParentheses(expression);
        if (value.kind().equals("functionExpression") && !value.children().isEmpty()) {
            SyntaxNode name = value.children().get(0);
            return name.kind().equals("identifier") && text(name, source).startsWith("$tl$callable$")
                    ? value : null;
        }

        // Lexical lowering also emits (function(captures) { return function $tl$callable$N(...) {
        // ... }; })(bindings).bind(this). The bind receiver is the factory's RESULT, not the
        // function literal. Follow only a direct return from an immediately invoked anonymous
        // factory; searching its descendants could select an unrelated nested callback or arity.
        if (!value.kind().equals("argumentsExpression") || value.children().size() != 2) {
            return null;
        }
        SyntaxNode factory = unwrapParentheses(value.children().get(0));
        if (!factory.kind().equals("functionExpression") || factory.children().size() != 2
                || !factory.children().get(0).kind().equals("parameters")) {
            return null;
        }
        SyntaxNode body = factory.children().get(1);
        if (!body.kind().equals("functionBody") || body.children().size() != 1) {
            return null;
        }
        SyntaxNode returned = body.children().get(0);
        return returned.kind().equals("returnStatement") && returned.children().size() == 1
                ? generatedFunctionValue(returned.children().get(0), source) : null;
    }

    private static SyntaxNode unwrapParentheses(SyntaxNode expression) {
        while (expression.kind().equals("parenthesizedExpression") && expression.children().size() == 1) {
            expression = expression.children().get(0);
        }
        return expression;
    }

    private static String wrapper(int arity) {
        StringJoiner parameters = new StringJoiner(", ");
        for (int index = 0; index < arity; index++) {
            parameters.add("$tsmodify$arg" + index);
        }
        // Keep the bound function itself: it already owns lexical this/arguments and closure state.
        // A named ordinary function preserves arity and rejects construction just as the emitted
        // arrow does. All helper names are private to the factory, outside the original expression.
        return "(function($tsmodify$bound) { return function $tsmodify$callback(" + parameters + ") { "
                + "if (this instanceof $tsmodify$callback) throw new TypeError(\"Function is not a constructor\"); "
                + "return $tsmodify$bound.apply(this, arguments); }; })";
    }

    private static String text(SyntaxNode node, String source) {
        return source.substring(node.range().start(), node.range().end());
    }

    private record Insertion(int offset, String text) {
    }
}
