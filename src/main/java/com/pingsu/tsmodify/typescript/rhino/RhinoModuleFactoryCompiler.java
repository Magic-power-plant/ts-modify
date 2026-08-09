package com.pingsu.tsmodify.typescript.rhino;

import dev.latvian.mods.rhino.ErrorReporter;
import dev.latvian.mods.rhino.Evaluator;
import dev.latvian.mods.rhino.Function;
import dev.latvian.mods.rhino.Scriptable;

/**
 * Capability exposed by the Rhino Context Mixin for compiling CommonJS module factories directly.
 */
public interface RhinoModuleFactoryCompiler {
    default Function compileModuleFactory(Scriptable scope, String source, String sourceName) {
        return tsmodify$compileFunction(scope, source, null, null, sourceName, 1, null);
    }

    Function tsmodify$compileFunction(
            Scriptable scope,
            String source,
            Evaluator compiler,
            ErrorReporter compilationErrorReporter,
            String sourceName,
            int lineNumber,
            Object securityDomain);
}
