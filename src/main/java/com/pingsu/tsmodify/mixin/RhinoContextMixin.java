package com.pingsu.tsmodify.mixin;

import com.pingsu.tsmodify.typescript.rhino.RhinoModuleFactoryCompiler;
import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.ErrorReporter;
import dev.latvian.mods.rhino.Evaluator;
import dev.latvian.mods.rhino.Function;
import dev.latvian.mods.rhino.Scriptable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes Rhino's direct function compiler for cold TypeScript module factories.
 */
@Mixin(value = Context.class, remap = false)
public abstract class RhinoContextMixin implements RhinoModuleFactoryCompiler {
    @Invoker(value = "compileFunction", remap = false)
    @Override
    public abstract Function tsmodify$compileFunction(
            Scriptable scope,
            String source,
            Evaluator compiler,
            ErrorReporter compilationErrorReporter,
            String sourceName,
            int lineNumber,
            Object securityDomain);
}
