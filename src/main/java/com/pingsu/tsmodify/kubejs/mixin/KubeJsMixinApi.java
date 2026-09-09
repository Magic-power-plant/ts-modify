package com.pingsu.tsmodify.kubejs.mixin;

import dev.latvian.mods.kubejs.script.ScriptManager;
import dev.latvian.mods.kubejs.script.ScriptType;
import dev.latvian.mods.rhino.Context;

import java.util.Objects;

/**
 * Startup-script binding for safe, exact method hooks.
 */
public final class KubeJsMixinApi {
    private final ScriptManager scriptManager;

    public KubeJsMixinApi(ScriptManager scriptManager) {
        this.scriptManager = Objects.requireNonNull(scriptManager, "KubeJS script manager cannot be null");
    }

    public KubeJsMixinRegistration before(
            String targetClass,
            String methodName,
            String methodDescriptor,
            Object callback) {
        return register(targetClass, methodName, methodDescriptor, KubeJsMixinHook.BEFORE, false, callback);
    }

    public KubeJsMixinRegistration cancellableBefore(
            String targetClass,
            String methodName,
            String methodDescriptor,
            Object callback) {
        return register(targetClass, methodName, methodDescriptor, KubeJsMixinHook.BEFORE, true, callback);
    }

    public KubeJsMixinRegistration after(
            String targetClass,
            String methodName,
            String methodDescriptor,
            Object callback) {
        return register(targetClass, methodName, methodDescriptor, KubeJsMixinHook.AFTER, false, callback);
    }

    /**
     * Changes target-method parameter values before its body begins.
     */
    public KubeJsMixinRegistration modifyArgs(
            String targetClass,
            String methodName,
            String methodDescriptor,
            Object callback) {
        return register(targetClass, methodName, methodDescriptor, KubeJsMixinHook.MODIFY_ARGS, false, callback);
    }

    /**
     * Changes every normal non-void return value.
     */
    public KubeJsMixinRegistration modifyReturnValue(
            String targetClass,
            String methodName,
            String methodDescriptor,
            Object callback) {
        return register(
                targetClass,
                methodName,
                methodDescriptor,
                KubeJsMixinHook.MODIFY_RETURN_VALUE,
                false,
                callback);
    }

    /**
     * Replaces a target method body while the callback is active. A non-void replacement must
     * set {@code event.returnValue}; callback failure falls through to the original body.
     */
    public KubeJsMixinRegistration replace(
            String targetClass,
            String methodName,
            String methodDescriptor,
            Object callback) {
        return register(targetClass, methodName, methodDescriptor, KubeJsMixinHook.REPLACE, false, callback);
    }

    public KubeJsMixinRegistration redirect(
            String targetClass,
            String methodName,
            String methodDescriptor,
            String instructionOwner,
            String instructionMethodName,
            String instructionMethodDescriptor,
            Object callback) {
        return redirect(
                targetClass,
                methodName,
                methodDescriptor,
                instructionOwner,
                instructionMethodName,
                instructionMethodDescriptor,
                0,
                callback);
    }

    /**
     * Replaces one exact method invocation instruction. The ordinal is zero-based among matching
     * owner/name/descriptor instructions in the target method.
     */
    public KubeJsMixinRegistration redirect(
            String targetClass,
            String methodName,
            String methodDescriptor,
            String instructionOwner,
            String instructionMethodName,
            String instructionMethodDescriptor,
            int ordinal,
            Object callback) {
        return register(
                targetClass,
                methodName,
                methodDescriptor,
                KubeJsMixinHook.REDIRECT,
                false,
                new KubeJsMixinInstructionTarget.Invoke(
                        instructionOwner,
                        instructionMethodName,
                        instructionMethodDescriptor,
                        ordinal,
                        -1),
                callback);
    }

    public KubeJsMixinRegistration modifyArg(
            String targetClass,
            String methodName,
            String methodDescriptor,
            String instructionOwner,
            String instructionMethodName,
            String instructionMethodDescriptor,
            int argumentIndex,
            Object callback) {
        return modifyArg(
                targetClass,
                methodName,
                methodDescriptor,
                instructionOwner,
                instructionMethodName,
                instructionMethodDescriptor,
                0,
                argumentIndex,
                callback);
    }

    /**
     * Changes one argument passed to an exact invocation instruction.
     */
    public KubeJsMixinRegistration modifyArg(
            String targetClass,
            String methodName,
            String methodDescriptor,
            String instructionOwner,
            String instructionMethodName,
            String instructionMethodDescriptor,
            int ordinal,
            int argumentIndex,
            Object callback) {
        return register(
                targetClass,
                methodName,
                methodDescriptor,
                KubeJsMixinHook.MODIFY_ARG,
                false,
                new KubeJsMixinInstructionTarget.Invoke(
                        instructionOwner,
                        instructionMethodName,
                        instructionMethodDescriptor,
                        ordinal,
                        argumentIndex),
                callback);
    }

    public KubeJsMixinRegistration modifyVariable(
            String targetClass,
            String methodName,
            String methodDescriptor,
            int localIndex,
            String variableDescriptor,
            String access,
            Object callback) {
        return modifyVariable(
                targetClass,
                methodName,
                methodDescriptor,
                localIndex,
                variableDescriptor,
                access,
                0,
                callback);
    }

    /**
     * Changes a value read from or written to one exact local-variable instruction.
     */
    public KubeJsMixinRegistration modifyVariable(
            String targetClass,
            String methodName,
            String methodDescriptor,
            int localIndex,
            String variableDescriptor,
            String access,
            int ordinal,
            Object callback) {
        return register(
                targetClass,
                methodName,
                methodDescriptor,
                KubeJsMixinHook.MODIFY_VARIABLE,
                false,
                new KubeJsMixinInstructionTarget.Variable(
                        localIndex,
                        variableDescriptor,
                        KubeJsMixinInstructionTarget.VariableAccess.fromScriptValue(access),
                        ordinal),
                callback);
    }

    private KubeJsMixinRegistration register(
            String targetClass,
            String methodName,
            String methodDescriptor,
            KubeJsMixinHook hook,
            boolean cancellable,
            Object callback) {
        return register(targetClass, methodName, methodDescriptor, hook, cancellable, null, callback);
    }

    private KubeJsMixinRegistration register(
            String targetClass,
            String methodName,
            String methodDescriptor,
            KubeJsMixinHook hook,
            boolean cancellable,
            KubeJsMixinInstructionTarget instructionTarget,
            Object callback) {
        requireStartupRegistration();
        Objects.requireNonNull(callback, "KubeJS mixin callback cannot be null");
        KubeJsMixinCallback handler = (KubeJsMixinCallback) Context.jsToJava(
                scriptManager.context,
                callback,
                KubeJsMixinCallback.class);
        int[] line = new int[1];
        String source = Context.getSourcePositionFromStack(scriptManager.context, line);
        return KubeJsMixinRuleRegistry.register(
                targetClass,
                methodName,
                methodDescriptor,
                hook,
                cancellable,
                instructionTarget,
                source,
                line[0],
                handler);
    }

    private void requireStartupRegistration() {
        if (scriptManager.scriptType != ScriptType.STARTUP) {
            throw new IllegalStateException("KubeJS mixin hooks can only be registered from startup_scripts");
        }
        if (!scriptManager.firstLoad || !scriptManager.canListenEvents) {
            throw new IllegalStateException("KubeJS mixin hooks can only be registered during the first startup script load; restart the game after changing them");
        }
    }
}
