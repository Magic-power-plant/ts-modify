package com.pingsu.tsmodify.kubejs.mixin;

/**
 * Static entry points inserted into transformed target methods.
 */
public final class KubeJsMixinRuntime {
    private KubeJsMixinRuntime() {
    }

    public static void before(String ruleId, Object receiver, Object[] arguments) {
        KubeJsMixinRuleRegistry.invokeBefore(ruleId, receiver, arguments);
    }

    public static KubeJsMixinRuntimeResult cancellableBefore(String ruleId, Object receiver, Object[] arguments) {
        return KubeJsMixinRuleRegistry.invokeCancellableBefore(ruleId, receiver, arguments);
    }

    public static Object[] modifyArguments(String ruleId, Object receiver, Object[] arguments) {
        return KubeJsMixinRuleRegistry.invokeModifyArguments(ruleId, receiver, arguments);
    }

    public static void after(String ruleId, Object receiver, Object[] arguments, Object returnValue) {
        KubeJsMixinRuleRegistry.invokeAfter(ruleId, receiver, arguments, returnValue);
    }

    public static Object modifyReturnValue(
            String ruleId,
            Object receiver,
            Object[] arguments,
            Object returnValue) {
        return KubeJsMixinRuleRegistry.invokeModifyReturnValue(ruleId, receiver, arguments, returnValue);
    }

    public static KubeJsMixinRuntimeResult replace(String ruleId, Object receiver, Object[] arguments) {
        return KubeJsMixinRuleRegistry.invokeReplace(ruleId, receiver, arguments);
    }

    public static KubeJsMixinRuntimeResult redirect(
            String ruleId,
            Object receiver,
            Object[] arguments,
            Object instructionReceiver,
            Object[] instructionArguments) {
        return KubeJsMixinRuleRegistry.invokeRedirect(
                ruleId,
                receiver,
                arguments,
                instructionReceiver,
                instructionArguments);
    }

    public static Object modifyArg(
            String ruleId,
            Object receiver,
            Object[] arguments,
            Object instructionReceiver,
            Object[] instructionArguments,
            Object value) {
        return KubeJsMixinRuleRegistry.invokeModifyArg(
                ruleId,
                receiver,
                arguments,
                instructionReceiver,
                instructionArguments,
                value);
    }

    public static Object modifyVariable(
            String ruleId,
            Object receiver,
            Object[] arguments,
            Object value) {
        return KubeJsMixinRuleRegistry.invokeModifyVariable(ruleId, receiver, arguments, value);
    }
}
