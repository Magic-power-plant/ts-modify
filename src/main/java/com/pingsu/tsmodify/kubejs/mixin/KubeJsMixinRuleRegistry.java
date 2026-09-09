package com.pingsu.tsmodify.kubejs.mixin;

import org.objectweb.asm.tree.ClassNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Keeps script callback state separate from the one-way class transformations installed in ModLauncher.
 */
public final class KubeJsMixinRuleRegistry {
    private static final Object LOCK = new Object();
    private static final AtomicLong NEXT_ID = new AtomicLong();
    private static final Map<String, List<AbstractKubeJsMixinRule>> RULES_BY_TARGET = new HashMap<>();
    private static final Map<String, AbstractKubeJsMixinRule> RULES_BY_ID = new HashMap<>();
    private static final Set<String> TRANSFORMING_TARGETS = new HashSet<>();

    private KubeJsMixinRuleRegistry() {
    }

    static KubeJsMixinRegistration register(
            String targetClass,
            String methodName,
            String methodDescriptor,
            KubeJsMixinHook hook,
            boolean cancellable,
            String source,
            int line,
            KubeJsMixinCallback callback) {
        return register(
                targetClass,
                methodName,
                methodDescriptor,
                hook,
                cancellable,
                null,
                source,
                line,
                callback);
    }

    static KubeJsMixinRegistration register(
            String targetClass,
            String methodName,
            String methodDescriptor,
            KubeJsMixinHook hook,
            boolean cancellable,
            KubeJsMixinInstructionTarget instructionTarget,
            String source,
            int line,
            KubeJsMixinCallback callback) {
        Objects.requireNonNull(hook, "KubeJS mixin hook cannot be null");
        Objects.requireNonNull(callback, "KubeJS mixin callback cannot be null");
        KubeJsMixinTargetPolicy.validate(
                targetClass,
                methodName,
                methodDescriptor,
                hook,
                cancellable,
                instructionTarget);
        KubeJsMixinScriptRule rule = newRule(
                targetClass,
                methodName,
                methodDescriptor,
                hook,
                cancellable,
                instructionTarget,
                source,
                line,
                callback);
        synchronized (LOCK) {
            if (TRANSFORMING_TARGETS.contains(targetClass)) {
                throw new IllegalStateException(
                        "KubeJS mixin target is currently being transformed; register hooks before class loading: "
                                + targetClass);
            }
            add(rule);
            try {
                ModLauncherMixinTransformerInstaller.install(targetClass);
            } catch (RuntimeException | LinkageError exception) {
                remove(rule);
                throw exception;
            }
        }
        return new KubeJsMixinRegistration(rule);
    }

    static KubeJsMixinRegistration registerForTesting(
            String targetClass,
            String methodName,
            String methodDescriptor,
            KubeJsMixinHook hook,
            boolean cancellable,
            KubeJsMixinCallback callback) {
        return registerForTesting(
                targetClass,
                methodName,
                methodDescriptor,
                hook,
                cancellable,
                null,
                callback);
    }

    static KubeJsMixinRegistration registerForTesting(
            String targetClass,
            String methodName,
            String methodDescriptor,
            KubeJsMixinHook hook,
            boolean cancellable,
            KubeJsMixinInstructionTarget instructionTarget,
            KubeJsMixinCallback callback) {
        Objects.requireNonNull(hook, "KubeJS mixin hook cannot be null");
        Objects.requireNonNull(callback, "KubeJS mixin callback cannot be null");
        KubeJsMixinTargetPolicy.validate(
                targetClass,
                methodName,
                methodDescriptor,
                hook,
                cancellable,
                instructionTarget);
        KubeJsMixinScriptRule rule = newRule(
                targetClass,
                methodName,
                methodDescriptor,
                hook,
                cancellable,
                instructionTarget,
                "test",
                0,
                callback);
        synchronized (LOCK) {
            if (TRANSFORMING_TARGETS.contains(targetClass)) {
                throw new IllegalStateException(
                        "KubeJS mixin target is currently being transformed: " + targetClass);
            }
            add(rule);
        }
        return new KubeJsMixinRegistration(rule);
    }

    private static KubeJsMixinScriptRule newRule(
            String targetClass,
            String methodName,
            String methodDescriptor,
            KubeJsMixinHook hook,
            boolean cancellable,
            KubeJsMixinInstructionTarget instructionTarget,
            String source,
            int line,
            KubeJsMixinCallback callback) {
        String id = "tsmodify:kubejs:" + NEXT_ID.incrementAndGet();
        return new KubeJsMixinScriptRule(
                id,
                targetClass,
                methodName,
                methodDescriptor,
                hook,
                cancellable,
                instructionTarget,
                source == null ? "<unknown>" : source,
                line,
                callback);
    }

    private static void add(AbstractKubeJsMixinRule rule) {
        RULES_BY_TARGET.computeIfAbsent(rule.targetClass(), ignored -> new ArrayList<>()).add(rule);
        RULES_BY_ID.put(rule.id(), rule);
    }

    private static void remove(AbstractKubeJsMixinRule rule) {
        rule.clear();
        RULES_BY_ID.remove(rule.id());
        List<AbstractKubeJsMixinRule> rules = RULES_BY_TARGET.get(rule.targetClass());
        if (rules == null) {
            return;
        }
        rules.remove(rule);
        if (rules.isEmpty()) {
            RULES_BY_TARGET.remove(rule.targetClass());
        }
    }

    static boolean hasActiveRules(String targetClass) {
        synchronized (LOCK) {
            List<AbstractKubeJsMixinRule> rules = RULES_BY_TARGET.get(targetClass);
            return rules != null && rules.stream().anyMatch(AbstractKubeJsMixinRule::isActive);
        }
    }

    static void transform(String targetClass, ClassNode targetNode) {
        Objects.requireNonNull(targetClass, "KubeJS mixin target class cannot be null");
        Objects.requireNonNull(targetNode, "KubeJS mixin target class node cannot be null");
        if (targetNode.name == null || !targetClass.equals(targetNode.name.replace('/', '.'))) {
            throw new KubeJsMixinTransformationException(
                    "KubeJS mixin registry received an unexpected class node for " + targetClass);
        }
        List<AbstractKubeJsMixinRule> rules;
        synchronized (LOCK) {
            List<AbstractKubeJsMixinRule> registeredRules = RULES_BY_TARGET.get(targetClass);
            if (registeredRules == null || registeredRules.isEmpty()) {
                return;
            }
            rules = registeredRules.stream().filter(AbstractKubeJsMixinRule::isActive).toList();
            TRANSFORMING_TARGETS.add(targetClass);
        }
        try {
            if (!rules.isEmpty()) {
                KubeJsMixinBytecodeInjector.inject(targetNode, rules);
            }
        } finally {
            synchronized (LOCK) {
                TRANSFORMING_TARGETS.remove(targetClass);
            }
        }
    }

    static void invokeBefore(String ruleId, Object receiver, Object[] arguments) {
        AbstractKubeJsMixinRule rule = findActiveRule(ruleId, KubeJsMixinHook.BEFORE);
        if (rule != null) {
            rule.invoke(receiver, arguments, null);
        }
    }

    static KubeJsMixinRuntimeResult invokeCancellableBefore(
            String ruleId,
            Object receiver,
            Object[] arguments) {
        AbstractKubeJsMixinRule rule = findActiveRule(ruleId, KubeJsMixinHook.BEFORE);
        if (rule == null) {
            return KubeJsMixinRuntimeResult.notApplied();
        }
        KubeJsMixinInvocation invocation = rule.invoke(receiver, arguments, null);
        if (invocation == null || !invocation.isCancelled()) {
            return KubeJsMixinRuntimeResult.notApplied();
        }
        return KubeJsMixinRuntimeResult.applied(invocation.getReturnValue());
    }

    static Object[] invokeModifyArguments(String ruleId, Object receiver, Object[] arguments) {
        AbstractKubeJsMixinRule rule = findActiveRule(ruleId, KubeJsMixinHook.MODIFY_ARGS);
        if (rule == null) {
            return arguments;
        }
        KubeJsMixinInvocation invocation = rule.invoke(receiver, arguments, null);
        return invocation == null ? arguments : invocation.currentArguments();
    }

    static void invokeAfter(String ruleId, Object receiver, Object[] arguments, Object returnValue) {
        AbstractKubeJsMixinRule rule = findActiveRule(ruleId, KubeJsMixinHook.AFTER);
        if (rule != null) {
            rule.invoke(receiver, arguments, returnValue);
        }
    }

    static Object invokeModifyReturnValue(
            String ruleId,
            Object receiver,
            Object[] arguments,
            Object returnValue) {
        AbstractKubeJsMixinRule rule = findActiveRule(ruleId, KubeJsMixinHook.MODIFY_RETURN_VALUE);
        if (rule == null) {
            return returnValue;
        }
        KubeJsMixinInvocation invocation = rule.invoke(receiver, arguments, returnValue);
        return invocation == null ? returnValue : invocation.getReturnValue();
    }

    static KubeJsMixinRuntimeResult invokeReplace(String ruleId, Object receiver, Object[] arguments) {
        AbstractKubeJsMixinRule rule = findActiveRule(ruleId, KubeJsMixinHook.REPLACE);
        if (rule == null) {
            return KubeJsMixinRuntimeResult.notApplied();
        }
        KubeJsMixinInvocation invocation = rule.invoke(receiver, arguments, null);
        return invocation == null
                ? KubeJsMixinRuntimeResult.notApplied()
                : KubeJsMixinRuntimeResult.applied(invocation.getReturnValue());
    }

    static KubeJsMixinRuntimeResult invokeRedirect(
            String ruleId,
            Object receiver,
            Object[] arguments,
            Object instructionReceiver,
            Object[] instructionArguments) {
        AbstractKubeJsMixinRule rule = findActiveRule(ruleId, KubeJsMixinHook.REDIRECT);
        if (rule == null) {
            return KubeJsMixinRuntimeResult.notApplied();
        }
        KubeJsMixinInvocation invocation = rule.invoke(
                receiver,
                arguments,
                null,
                instructionReceiver,
                instructionArguments,
                null);
        return invocation == null
                ? KubeJsMixinRuntimeResult.notApplied()
                : KubeJsMixinRuntimeResult.applied(invocation.getReturnValue());
    }

    static Object invokeModifyArg(
            String ruleId,
            Object receiver,
            Object[] arguments,
            Object instructionReceiver,
            Object[] instructionArguments,
            Object value) {
        AbstractKubeJsMixinRule rule = findActiveRule(ruleId, KubeJsMixinHook.MODIFY_ARG);
        if (rule == null) {
            return value;
        }
        KubeJsMixinInvocation invocation = rule.invoke(
                receiver,
                arguments,
                null,
                instructionReceiver,
                instructionArguments,
                value);
        return invocation == null ? value : invocation.getValue();
    }

    static Object invokeModifyVariable(
            String ruleId,
            Object receiver,
            Object[] arguments,
            Object value) {
        AbstractKubeJsMixinRule rule = findActiveRule(ruleId, KubeJsMixinHook.MODIFY_VARIABLE);
        if (rule == null) {
            return value;
        }
        KubeJsMixinInvocation invocation = rule.invoke(receiver, arguments, null, null, null, value);
        return invocation == null ? value : invocation.getValue();
    }

    private static AbstractKubeJsMixinRule findActiveRule(String ruleId, KubeJsMixinHook hook) {
        synchronized (LOCK) {
            AbstractKubeJsMixinRule rule = RULES_BY_ID.get(ruleId);
            return rule != null && rule.hook() == hook && rule.isActive() ? rule : null;
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            RULES_BY_ID.values().forEach(AbstractKubeJsMixinRule::clear);
            RULES_BY_ID.clear();
            RULES_BY_TARGET.clear();
            TRANSFORMING_TARGETS.clear();
        }
    }
}
