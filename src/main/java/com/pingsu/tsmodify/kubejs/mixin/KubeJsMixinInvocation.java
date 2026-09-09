package com.pingsu.tsmodify.kubejs.mixin;

import org.objectweb.asm.Type;

import java.util.Arrays;
import java.util.Objects;

/**
 * Call metadata passed to KubeJS callbacks.
 *
 * <p>Most properties are read-only. The operation that created an invocation determines whether
 * method arguments, a return value, or one instruction value can be written.</p>
 */
public final class KubeJsMixinInvocation {
    private final String targetClass;
    private final String methodName;
    private final String methodDescriptor;
    private final KubeJsMixinHook hook;
    private final boolean cancellable;
    private final Object receiver;
    private final Object[] arguments;
    private final Type[] argumentTypes;
    private final Type returnValueType;
    private final KubeJsMixinInstructionTarget instructionTarget;
    private final Object instructionReceiver;
    private final Object[] instructionArguments;
    private final Type valueType;
    private Object returnValue;
    private boolean returnValueSet;
    private Object value;
    private boolean valueSet;
    private boolean cancelled;

    KubeJsMixinInvocation(
            String targetClass,
            String methodName,
            String methodDescriptor,
            KubeJsMixinHook hook,
            boolean cancellable,
            Object receiver,
            Object[] arguments,
            Object returnValue) {
        this(
                targetClass,
                methodName,
                methodDescriptor,
                hook,
                cancellable,
                receiver,
                arguments,
                returnValue,
                null,
                null,
                null,
                null);
    }

    KubeJsMixinInvocation(
            String targetClass,
            String methodName,
            String methodDescriptor,
            KubeJsMixinHook hook,
            boolean cancellable,
            Object receiver,
            Object[] arguments,
            Object returnValue,
            KubeJsMixinInstructionTarget instructionTarget,
            Object instructionReceiver,
            Object[] instructionArguments,
            Object value) {
        this.targetClass = Objects.requireNonNull(targetClass, "KubeJS mixin target class cannot be null");
        this.methodName = Objects.requireNonNull(methodName, "KubeJS mixin method name cannot be null");
        this.methodDescriptor = Objects.requireNonNull(
                methodDescriptor,
                "KubeJS mixin method descriptor cannot be null");
        this.hook = Objects.requireNonNull(hook, "KubeJS mixin hook cannot be null");
        if (cancellable && hook != KubeJsMixinHook.BEFORE) {
            throw new IllegalArgumentException("KubeJS cancellable hooks must use the before hook");
        }
        this.cancellable = cancellable;
        this.receiver = receiver;
        Objects.requireNonNull(arguments, "KubeJS mixin arguments cannot be null");
        this.arguments = Arrays.copyOf(arguments, arguments.length);
        this.argumentTypes = Type.getArgumentTypes(methodDescriptor);
        this.returnValueType = resolveReturnValueType(methodDescriptor, hook, cancellable, instructionTarget);
        this.instructionTarget = instructionTarget;
        this.instructionReceiver = instructionReceiver;
        this.instructionArguments = instructionArguments == null
                ? null
                : Arrays.copyOf(instructionArguments, instructionArguments.length);
        this.valueType = resolveValueType(hook, instructionTarget);
        this.returnValue = returnValue;
        this.returnValueSet = false;
        this.value = value;
        this.valueSet = false;
        this.cancelled = false;
    }

    public String getTargetClass() {
        return targetClass;
    }

    public String getMethodName() {
        return methodName;
    }

    public String getMethodDescriptor() {
        return methodDescriptor;
    }

    public String getHook() {
        return hook.displayName();
    }

    public boolean isCancellable() {
        return cancellable;
    }

    public Object getReceiver() {
        return receiver;
    }

    public Object[] getArguments() {
        return Arrays.copyOf(arguments, arguments.length);
    }

    public Object getArgument(int index) {
        return arguments[checkArgumentIndex(index)];
    }

    /**
     * Changes one target-method argument for a {@code modifyArgs} rule.
     */
    public void setArgument(int index, Object argument) {
        requireArgumentMutation();
        int checkedIndex = checkArgumentIndex(index);
        arguments[checkedIndex] = KubeJsMixinValueConverter.coerce(argument, argumentTypes[checkedIndex]);
    }

    /**
     * Replaces all target-method arguments for a {@code modifyArgs} rule.
     */
    public void setArguments(Object[] replacementArguments) {
        requireArgumentMutation();
        Objects.requireNonNull(replacementArguments, "KubeJS mixin replacement arguments cannot be null");
        if (replacementArguments.length != arguments.length) {
            throw new IllegalArgumentException(
                    "KubeJS mixin expected " + arguments.length + " arguments but received "
                            + replacementArguments.length);
        }
        for (int index = 0; index < arguments.length; index++) {
            arguments[index] = KubeJsMixinValueConverter.coerce(replacementArguments[index], argumentTypes[index]);
        }
    }

    public Object getReturnValue() {
        return returnValue;
    }

    /**
     * Changes the return value for {@code modifyReturnValue}, {@code replace}, {@code redirect},
     * or a cancelled non-void {@code cancellableBefore} rule.
     */
    public void setReturnValue(Object returnValue) {
        if (!hook.supportsReturnValueMutation(cancellable) || returnValueType == null) {
            throw new IllegalStateException("This KubeJS mixin hook cannot change its return value");
        }
        this.returnValue = KubeJsMixinValueConverter.coerce(returnValue, returnValueType);
        this.returnValueSet = true;
    }

    public boolean isReturnValueSet() {
        return returnValueSet;
    }

    /**
     * Returns the receiver of an invocation instruction selected by {@code redirect} or
     * {@code modifyArg}, or {@code null} for a static instruction.
     */
    public Object getInstructionReceiver() {
        return instructionReceiver;
    }

    /**
     * Returns a defensive copy of the arguments at an instruction-level invocation target.
     */
    public Object[] getInstructionArguments() {
        return instructionArguments == null
                ? null
                : Arrays.copyOf(instructionArguments, instructionArguments.length);
    }

    public String getInstructionOwner() {
        if (instructionTarget instanceof KubeJsMixinInstructionTarget.Invoke invoke) {
            return invoke.owner().replace('/', '.');
        }
        return null;
    }

    public String getInstructionMethodName() {
        if (instructionTarget instanceof KubeJsMixinInstructionTarget.Invoke invoke) {
            return invoke.methodName();
        }
        return null;
    }

    public String getInstructionMethodDescriptor() {
        if (instructionTarget instanceof KubeJsMixinInstructionTarget.Invoke invoke) {
            return invoke.methodDescriptor();
        }
        return null;
    }

    public int getInstructionOrdinal() {
        return instructionTarget instanceof KubeJsMixinInstructionTarget.Invoke invoke ? invoke.ordinal() : -1;
    }

    /**
     * Returns the selected value for {@code modifyArg} and {@code modifyVariable}.
     */
    public Object getValue() {
        if (valueType == null) {
            throw new IllegalStateException("This KubeJS mixin hook does not expose one mutable value");
        }
        return value;
    }

    /**
     * Changes the selected value for {@code modifyArg} and {@code modifyVariable}.
     */
    public void setValue(Object value) {
        if (!hook.supportsValueMutation() || valueType == null) {
            throw new IllegalStateException("This KubeJS mixin hook cannot change an instruction value");
        }
        this.value = KubeJsMixinValueConverter.coerce(value, valueType);
        this.valueSet = true;
    }

    public boolean isValueSet() {
        return valueSet;
    }

    public Object getVariableValue() {
        requireVariableTarget();
        return getValue();
    }

    public void setVariableValue(Object value) {
        requireVariableTarget();
        setValue(value);
    }

    public int getVariableIndex() {
        if (instructionTarget instanceof KubeJsMixinInstructionTarget.Variable variable) {
            return variable.localIndex();
        }
        return -1;
    }

    public String getVariableDescriptor() {
        if (instructionTarget instanceof KubeJsMixinInstructionTarget.Variable variable) {
            return variable.descriptor();
        }
        return null;
    }

    public String getVariableAccess() {
        if (instructionTarget instanceof KubeJsMixinInstructionTarget.Variable variable) {
            return variable.access().displayName();
        }
        return null;
    }

    public void cancel() {
        if (!cancellable || hook != KubeJsMixinHook.BEFORE) {
            throw new IllegalStateException("This KubeJS mixin hook cannot cancel its target method");
        }
        cancelled = true;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    Object[] currentArguments() {
        return Arrays.copyOf(arguments, arguments.length);
    }

    boolean requiresExplicitReturnValue() {
        if (returnValueType == null) {
            return false;
        }
        return hook == KubeJsMixinHook.REPLACE
                || hook == KubeJsMixinHook.REDIRECT
                || (cancellable && cancelled);
    }

    private static Type resolveReturnValueType(
            String methodDescriptor,
            KubeJsMixinHook hook,
            boolean cancellable,
            KubeJsMixinInstructionTarget instructionTarget) {
        Type candidate;
        if (hook == KubeJsMixinHook.REDIRECT
                && instructionTarget instanceof KubeJsMixinInstructionTarget.Invoke invoke) {
            candidate = invoke.returnType();
        } else if (hook.supportsReturnValueMutation(cancellable)) {
            candidate = Type.getReturnType(methodDescriptor);
        } else {
            return null;
        }
        return candidate.getSort() == Type.VOID ? null : candidate;
    }

    private static Type resolveValueType(
            KubeJsMixinHook hook,
            KubeJsMixinInstructionTarget instructionTarget) {
        if (hook == KubeJsMixinHook.MODIFY_ARG
                && instructionTarget instanceof KubeJsMixinInstructionTarget.Invoke invoke) {
            return invoke.argumentTypes()[invoke.argumentIndex()];
        }
        if (hook == KubeJsMixinHook.MODIFY_VARIABLE
                && instructionTarget instanceof KubeJsMixinInstructionTarget.Variable variable) {
            return variable.type();
        }
        return null;
    }

    private int checkArgumentIndex(int index) {
        if (index < 0 || index >= arguments.length) {
            throw new IndexOutOfBoundsException(
                    "KubeJS mixin argument index " + index + " is outside 0.." + (arguments.length - 1));
        }
        return index;
    }

    private void requireArgumentMutation() {
        if (!hook.supportsArgumentMutation()) {
            throw new IllegalStateException("This KubeJS mixin hook cannot change target method arguments");
        }
    }

    private void requireVariableTarget() {
        if (!(instructionTarget instanceof KubeJsMixinInstructionTarget.Variable)) {
            throw new IllegalStateException("This KubeJS mixin hook does not target a local variable");
        }
    }
}
