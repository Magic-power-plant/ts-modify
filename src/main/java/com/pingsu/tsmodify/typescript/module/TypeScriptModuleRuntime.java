package com.pingsu.tsmodify.typescript.module;

import com.pingsu.tsmodify.typescript.TypeScriptErrorReporter;
import com.pingsu.tsmodify.typescript.TypeScriptTranspileException;
import dev.latvian.mods.kubejs.script.ScriptManager;
import dev.latvian.mods.kubejs.script.ScriptType;
import dev.latvian.mods.rhino.BaseFunction;
import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.Function;
import dev.latvian.mods.rhino.NativeObject;
import dev.latvian.mods.rhino.Scriptable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class TypeScriptModuleRuntime {
    private final TypeScriptScriptDomain domain;
    private final Context context;
    private final Scriptable topLevelScope;
    private final Map<String, ModuleExecution> executions;
    private final Deque<String> executionStack;

    public TypeScriptModuleRuntime(ScriptManager manager) {
        this(TypeScriptScriptDomain.from(manager.scriptType), manager.context, manager.topLevelScope);
    }

    public TypeScriptModuleRuntime(ScriptType scriptType, Context context, Scriptable topLevelScope) {
        this(TypeScriptScriptDomain.from(scriptType), context, topLevelScope);
    }

    public TypeScriptModuleRuntime(TypeScriptScriptDomain domain, Context context, Scriptable topLevelScope) {
        this.domain = domain;
        this.context = context;
        this.topLevelScope = topLevelScope;
        executions = new HashMap<>();
        executionStack = new ArrayDeque<>();
    }

    public Object requireSelf(String moduleId, Object scopeArg) {
        return load(moduleId, toScope(scopeArg));
    }

    public Object require(String specifier, String requesterId, Object scopeArg) {
        String moduleId;
        try {
            moduleId = TypeScriptModuleRegistry.resolve(domain, requesterId, specifier);
        } catch (RuntimeException failure) {
            throw TypeScriptErrorReporter.moduleResolutionFailure(domain.name(), requesterId, specifier, failure);
        }
        return load(moduleId, toScope(scopeArg));
    }

    private Object load(String moduleId, Scriptable scope) {
        if (moduleId == null || moduleId.isBlank()) {
            throw TypeScriptErrorReporter.moduleExecutionFailure(
                    "<missing>",
                    "<unknown>",
                    "",
                    List.of(),
                    new IllegalArgumentException("Cannot load a TypeScript module without an ID"));
        }
        ModuleExecution existing = executions.get(moduleId);
        if (existing != null) {
            return existing.exports;
        }

        executionStack.addLast(moduleId);
        TypeScriptModuleRegistry.ModuleSource source = null;
        try {
            source = TypeScriptModuleRegistry.getSource(domain, moduleId);
            if (source == null) {
                throw new TypeScriptTranspileException("TypeScript module '" + moduleId + "' is not registered");
            }

            NativeObject exports = new NativeObject(context);
            exports.setParentScope(scope);
            NativeObject module = new NativeObject(context);
            module.setParentScope(scope);
            module.put(context, "exports", module, exports);

            ModuleExecution execution = new ModuleExecution(exports);
            executions.put(moduleId, execution);
            Function factory = compileFactory(moduleId, source, scope);
            factory.call(context, scope, exports, new Object[]{
                    new RequireFunction(this, moduleId, scope),
                    exports,
                    module
            });

            Object moduleExports = module.get(context, "exports", module);
            execution.exports = moduleExports == Scriptable.NOT_FOUND ? exports : moduleExports;
            execution.loaded = true;
            return execution.exports;
        } catch (RuntimeException e) {
            executions.remove(moduleId);
            throw TypeScriptErrorReporter.moduleExecutionFailure(
                    moduleId,
                    source == null ? moduleId : source.sourceName(),
                    source == null ? "" : source.body(),
                    List.copyOf(executionStack),
                    e);
        } finally {
            executionStack.removeLast();
        }
    }

    private Function compileFactory(
            String moduleId,
            TypeScriptModuleRegistry.ModuleSource source,
            Scriptable scope) {
        String factorySource = "(function(require, exports, module) {\n" + source.body() + "\n})";
        Object value = context.evaluateString(scope, factorySource, source.sourceName(), 1, null);
        if (value instanceof Function function) {
            return function;
        }
        throw new TypeScriptTranspileException("TypeScript module '" + moduleId + "' did not compile to a function");
    }

    private Scriptable toScope(Object scopeArg) {
        return scopeArg instanceof Scriptable scriptable ? scriptable : topLevelScope;
    }

    private static final class ModuleExecution {
        private Object exports;
        private boolean loaded;

        private ModuleExecution(Object exports) {
            this.exports = exports;
        }
    }

    private static final class RequireFunction extends BaseFunction {
        private final TypeScriptModuleRuntime runtime;
        private final String requesterId;
        private final Scriptable moduleScope;

        private RequireFunction(TypeScriptModuleRuntime runtime, String requesterId, Scriptable moduleScope) {
            this.runtime = runtime;
            this.requesterId = requesterId;
            this.moduleScope = moduleScope;
            setParentScope(moduleScope);
        }

        @Override
        public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
            if (args.length == 0 || args[0] == null) {
                throw TypeScriptErrorReporter.moduleResolutionFailure(
                        runtime.domain.name(),
                        requesterId,
                        "<missing>",
                        new IllegalArgumentException("require() was called without a module specifier"));
            }
            return runtime.require(String.valueOf(args[0]), requesterId, moduleScope);
        }

        @Override
        public String getFunctionName() {
            return "require";
        }
    }
}
