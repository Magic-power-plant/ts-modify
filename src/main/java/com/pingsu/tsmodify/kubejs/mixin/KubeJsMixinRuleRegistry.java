package com.pingsu.tsmodify.kubejs.mixin;

import dev.latvian.mods.kubejs.KubeJS;
import org.objectweb.asm.tree.ClassNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
    private static final Set<String> REPORTED_TARGETS_WITHOUT_RULES = new HashSet<>();
    private static final Set<String> REPORTED_RETRANSFORM_UNAVAILABLE_TARGETS = new HashSet<>();
    private static final Set<String> PENDING_RETRANSFORM_TARGETS = new HashSet<>();
    private static final Set<String> RETRANSFORM_FAILED_TARGETS = new HashSet<>();
    private static boolean reportedCacheRetention;

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
            boolean pendingRetransform = false;
            try {
                // The two transformation paths are mutually exclusive (D5): an already-defined class
                // cannot be reached through ModLauncher's transform store, and re-adding it there
                // would only produce duplicate transformers for future loads.
                Class<?> loadedTarget = ModLauncherMixinTransformerInstaller.findLoadedTarget(targetClass);
                if (loadedTarget != null && KubeJsMixinRetransform.canRetransform(targetClass, loadedTarget)) {
                    PENDING_RETRANSFORM_TARGETS.add(targetClass);
                    pendingRetransform = true;
                } else {
                    ModLauncherMixinTransformerInstaller.install(targetClass);
                }
            } catch (RuntimeException | LinkageError exception) {
                remove(rule);
                if (pendingRetransform && !hasActiveRules(targetClass)) {
                    PENDING_RETRANSFORM_TARGETS.remove(targetClass);
                }
                throw exception;
            }
            logRegistration(rule);
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
        REPORTED_TARGETS_WITHOUT_RULES.remove(rule.targetClass());
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

    static void reportTargetWithoutActiveRules(String targetClass) {
        boolean report;
        synchronized (LOCK) {
            List<AbstractKubeJsMixinRule> rules = RULES_BY_TARGET.get(targetClass);
            boolean hasActiveRules = rules != null && rules.stream().anyMatch(AbstractKubeJsMixinRule::isActive);
            report = !hasActiveRules && REPORTED_TARGETS_WITHOUT_RULES.add(targetClass);
        }
        if (report) {
            KubeJS.LOGGER.warn(
                    "KubeJS mixin target {} is being defined without active rules; its hooks will not run. "
                            + "Rules were cleared, disabled, or registered too late before this one-shot class load.",
                    targetClass);
        }
    }

    /**
     * Applies every active rule of one target class to the given class node.
     *
     * <p>The ModLauncher path ignores the result; the retransformation path uses it to decide
     * whether a class node was changed at all, so it can return the original bytes and keep its
     * own output idempotent.</p>
     *
     * @return the number of rules that were actually injected, {@code 0} when the class node was
     *         left untouched
     */
    static int transform(String targetClass, ClassNode targetNode) {
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
                return 0;
            }
            rules = registeredRules.stream().filter(AbstractKubeJsMixinRule::isActive).toList();
            TRANSFORMING_TARGETS.add(targetClass);
        }
        try {
            if (rules.isEmpty()) {
                return 0;
            }
            return KubeJsMixinBytecodeInjector.inject(targetNode, rules);
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

    /**
     * Clears registered rules for tests or a future explicit reset operation.
     *
     * <p>This is intentionally not part of {@link dev.latvian.mods.kubejs.KubeJSPlugin#clearCaches()}.
     * It does not uninstall the ModLauncher transformers, and it cannot undo bytecode that was
     * already transformed or retransformed in this JVM. Retaining the installed transformers keeps
     * the registry's one-shot class-load behavior diagnosable instead of silently dropping it.</p>
     */
    public static void clear() {
        int clearedRuleCount;
        synchronized (LOCK) {
            clearedRuleCount = RULES_BY_ID.size();
            RULES_BY_ID.values().forEach(AbstractKubeJsMixinRule::clear);
            RULES_BY_ID.clear();
            RULES_BY_TARGET.clear();
            TRANSFORMING_TARGETS.clear();
            REPORTED_TARGETS_WITHOUT_RULES.clear();
            REPORTED_RETRANSFORM_UNAVAILABLE_TARGETS.clear();
            PENDING_RETRANSFORM_TARGETS.clear();
            RETRANSFORM_FAILED_TARGETS.clear();
            reportedCacheRetention = false;
        }
        if (clearedRuleCount > 0) {
            KubeJS.LOGGER.info(
                    "Cleared {} KubeJS mixin rule(s) after an explicit registry reset; transformed classes remain unchanged.",
                    clearedRuleCount);
        }
    }

    /**
     * Records a generic KubeJS cache clear without changing one-shot class-load rules.
     */
    public static void onKubeJsCacheClear() {
        String targets;
        int ruleCount;
        int pendingCount;
        int failedCount;
        synchronized (LOCK) {
            if (RULES_BY_ID.isEmpty() || reportedCacheRetention) {
                return;
            }
            ruleCount = RULES_BY_ID.size();
            targets = targetSummary();
            pendingCount = PENDING_RETRANSFORM_TARGETS.size();
            failedCount = RETRANSFORM_FAILED_TARGETS.size();
            reportedCacheRetention = true;
        }
        KubeJS.LOGGER.info(
                "KubeJS cleared general caches; retaining {} KubeJS mixin rule(s) for delayed class loads: {}. "
                        + "Pending retransform target(s): {}; failed retransform target(s): {}. "
                        + "Restart the game to change these registrations.",
                ruleCount,
                targets,
                pendingCount,
                failedCount);
    }

    /**
     * Applies every queued retransformation in a single VM operation.
     *
     * <p>The intended call sites are the mod-bus {@code FMLLoadCompleteEvent} listener
     * ({@link KubeJsMixinRetransformLifecycle}) and, defensively,
     * {@link com.pingsu.tsmodify.kubejs.TsmodifyKubeJSPlugin#clearCaches()}.</p>
     *
     * <p>Lock discipline (NFR4): the pending snapshot is taken under {@link #LOCK}, then the lock is
     * released before any class lookup, transformer installation, or {@code retransformClasses}
     * call, and it is re-acquired only to update the bookkeeping. A class that fails
     * re-verification or retransformation is removed from the pending set, recorded as failed, and
     * reported once; it is never retried, so a broken target cannot be retransformed repeatedly.</p>
     */
    public static void flushPendingRetransforms() {
        List<String> pendingTargets;
        synchronized (LOCK) {
            if (PENDING_RETRANSFORM_TARGETS.isEmpty()) {
                return;
            }
            pendingTargets = new ArrayList<>(PENDING_RETRANSFORM_TARGETS);
        }

        List<String> retransformableTargets = new ArrayList<>();
        List<Class<?>> retransformableClasses = new ArrayList<>();
        Map<String, String> unavailableTargets = new LinkedHashMap<>();
        for (String targetClass : pendingTargets) {
            Class<?> loadedTarget;
            try {
                loadedTarget = ModLauncherMixinTransformerInstaller.findLoadedTarget(targetClass);
            } catch (RuntimeException | LinkageError failure) {
                unavailableTargets.put(targetClass, "the loaded class could not be resolved: " + failure);
                continue;
            }
            if (!KubeJsMixinRetransform.canRetransform(targetClass, loadedTarget)) {
                unavailableTargets.put(targetClass, KubeJsMixinRetransform.retransformUnavailableReason(loadedTarget));
                continue;
            }
            retransformableTargets.add(targetClass);
            retransformableClasses.add(loadedTarget);
        }

        boolean retransformed = !retransformableTargets.isEmpty()
                && KubeJsMixinRetransform.trackTargets(retransformableTargets)
                && KubeJsMixinRetransform.retransformAll(retransformableClasses.toArray(new Class<?>[0]));

        synchronized (LOCK) {
            PENDING_RETRANSFORM_TARGETS.removeAll(pendingTargets);
            RETRANSFORM_FAILED_TARGETS.addAll(unavailableTargets.keySet());
            if (!retransformed) {
                RETRANSFORM_FAILED_TARGETS.addAll(retransformableTargets);
            }
        }

        unavailableTargets.forEach(KubeJsMixinRuleRegistry::reportRetransformUnavailable);
        if (!retransformableTargets.isEmpty()) {
            if (retransformed) {
                KubeJS.LOGGER.info(
                        "Retransformed {} already-loaded KubeJS mixin target(s): {}",
                        retransformableTargets.size(),
                        String.join(", ", retransformableTargets));
            } else {
                KubeJS.LOGGER.error(
                        "Could not retransform {} already-loaded KubeJS mixin target(s): {}; "
                                + "their registered hooks will not run",
                        retransformableTargets.size(),
                        String.join(", ", retransformableTargets));
            }
        }
    }

    /**
     * Reports one target that was queued for retransformation but cannot be retransformed, once per
     * target, in the same style as {@link #reportTargetWithoutActiveRules(String)}.
     */
    static void reportRetransformUnavailable(String targetClass, String reason) {
        boolean report;
        synchronized (LOCK) {
            report = REPORTED_RETRANSFORM_UNAVAILABLE_TARGETS.add(targetClass);
        }
        if (report) {
            KubeJS.LOGGER.warn(
                    "KubeJS mixin target {} is already defined but will not be retransformed: {}. "
                            + "Its registered hooks will not run.",
                    targetClass,
                    reason);
        }
    }

    /**
     * Read-only diagnostics accessor for the failed retransformation bookkeeping.
     */
    static boolean hasFailedRetransform(String targetClass) {
        synchronized (LOCK) {
            return RETRANSFORM_FAILED_TARGETS.contains(targetClass);
        }
    }

    private static void logRegistration(KubeJsMixinScriptRule rule) {
        KubeJS.LOGGER.info(
                "Registered KubeJS mixin rule {} for {}#{}{} ({}). {} rule(s) target: {}. "
                        + "Registrations remain active until the game restarts.",
                rule.id(),
                rule.targetClass(),
                rule.methodName(),
                rule.methodDescriptor(),
                rule.hook().displayName(),
                RULES_BY_ID.size(),
                targetSummary());
    }

    private static String targetSummary() {
        return RULES_BY_TARGET.keySet().stream().sorted().reduce((left, right) -> left + ", " + right).orElse("<none>");
    }
}
