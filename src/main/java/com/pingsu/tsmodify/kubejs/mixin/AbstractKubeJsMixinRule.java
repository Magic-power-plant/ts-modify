package com.pingsu.tsmodify.kubejs.mixin;

import dev.latvian.mods.kubejs.KubeJS;

import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns the lifecycle of one installed hook. A callback failure disables only this rule.
 */
abstract class AbstractKubeJsMixinRule {
    private static final ThreadLocal<Set<String>> ACTIVE_RULES = ThreadLocal.withInitial(HashSet::new);

    private final String id;
    private final String targetClass;
    private final String methodName;
    private final String methodDescriptor;
    private final KubeJsMixinHook hook;
    private final boolean cancellable;
    private final KubeJsMixinInstructionTarget instructionTarget;
    private final String source;
    private final int line;
    private final AtomicReference<KubeJsMixinRuleState> state;

    AbstractKubeJsMixinRule(
            String id,
            String targetClass,
            String methodName,
            String methodDescriptor,
            KubeJsMixinHook hook,
            boolean cancellable,
            KubeJsMixinInstructionTarget instructionTarget,
            String source,
            int line) {
        this.id = Objects.requireNonNull(id, "KubeJS mixin rule id cannot be null");
        this.targetClass = Objects.requireNonNull(targetClass, "KubeJS mixin target class cannot be null");
        this.methodName = Objects.requireNonNull(methodName, "KubeJS mixin method name cannot be null");
        this.methodDescriptor = Objects.requireNonNull(
                methodDescriptor,
                "KubeJS mixin method descriptor cannot be null");
        this.hook = Objects.requireNonNull(hook, "KubeJS mixin hook cannot be null");
        this.cancellable = cancellable;
        this.instructionTarget = instructionTarget;
        this.source = source == null ? "<unknown>" : source;
        this.line = line;
        this.state = new AtomicReference<>(KubeJsMixinRuleState.ACTIVE);
    }

    final String id() {
        return id;
    }

    final String targetClass() {
        return targetClass;
    }

    final String methodName() {
        return methodName;
    }

    final String methodDescriptor() {
        return methodDescriptor;
    }

    final KubeJsMixinHook hook() {
        return hook;
    }

    final boolean cancellable() {
        return cancellable;
    }

    final KubeJsMixinInstructionTarget instructionTarget() {
        return instructionTarget;
    }

    final boolean isActive() {
        return state.get() == KubeJsMixinRuleState.ACTIVE;
    }

    final String stateName() {
        return state.get().name().toLowerCase(Locale.ROOT);
    }

    final boolean disable() {
        return state.compareAndSet(KubeJsMixinRuleState.ACTIVE, KubeJsMixinRuleState.DISABLED);
    }

    final void clear() {
        state.set(KubeJsMixinRuleState.CLEARED);
    }

    final KubeJsMixinInvocation invoke(Object receiver, Object[] arguments, Object returnValue) {
        return invoke(receiver, arguments, returnValue, null, null, null);
    }

    final KubeJsMixinInvocation invoke(
            Object receiver,
            Object[] arguments,
            Object returnValue,
            Object instructionReceiver,
            Object[] instructionArguments,
            Object value) {
        if (!isActive()) {
            return null;
        }
        Objects.requireNonNull(arguments, "KubeJS mixin arguments cannot be null");

        Set<String> activeRules = ACTIVE_RULES.get();
        if (!activeRules.add(id)) {
            KubeJS.LOGGER.warn("Skipped re-entrant KubeJS mixin rule {}", id);
            return null;
        }

        try {
            KubeJsMixinInvocation invocation = new KubeJsMixinInvocation(
                    targetClass,
                    methodName,
                    methodDescriptor,
                    hook,
                    cancellable,
                    receiver,
                    arguments,
                    returnValue,
                    instructionTarget,
                    instructionReceiver,
                    instructionArguments,
                    value);
            invokeCallback(invocation);
            if (invocation.requiresExplicitReturnValue() && !invocation.isReturnValueSet()) {
                throw new IllegalStateException(
                        "KubeJS mixin " + hook.displayName()
                                + " callback must set a return value before replacing a non-void invocation");
            }
            return invocation;
        } catch (Throwable failure) {
            rethrowFatal(failure);
            if (disable()) {
                KubeJS.LOGGER.error(
                        "Disabled KubeJS mixin rule {} for {}#{}{} ({}) from {}:{} after callback failure",
                        id,
                        targetClass,
                        methodName,
                        methodDescriptor,
                        hook.displayName(),
                        source,
                        line,
                        failure);
            }
            return null;
        } finally {
            activeRules.remove(id);
            if (activeRules.isEmpty()) {
                ACTIVE_RULES.remove();
            }
        }
    }

    private static void rethrowFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError error) {
            throw error;
        }
        if (failure instanceof ThreadDeath death) {
            throw death;
        }
        if (failure instanceof LinkageError error) {
            throw error;
        }
    }

    protected abstract void invokeCallback(KubeJsMixinInvocation invocation);
}
