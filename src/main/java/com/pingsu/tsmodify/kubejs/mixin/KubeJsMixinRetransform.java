package com.pingsu.tsmodify.kubejs.mixin;

import dev.latvian.mods.kubejs.KubeJS;

import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.util.List;
import java.util.Objects;

/**
 * Detects the optional TS Modify Java agent and drives retransformation of already-loaded targets.
 *
 * <p>The agent jar is loaded by the system class loader while this mod is defined by ModLauncher's
 * transforming class loader, so the two sides cannot reference each other's types. The
 * {@link Instrumentation} instance is therefore read reflectively from
 * {@code com.pingsu.tsmodify.agent.TsModifyAgent#instrumentation()}. {@code Instrumentation} itself
 * is a platform-loader type, so the returned instance is used directly without any bridge.</p>
 *
 * <p>Detection is cached with double-checked locking. A missing agent is cached as well, so the
 * negative answer costs one lookup for the whole process. Without an agent every method here
 * degrades to the historical fail-closed behavior.</p>
 */
final class KubeJsMixinRetransform {
    private static final String AGENT_CLASS_NAME = "com.pingsu.tsmodify.agent.TsModifyAgent";
    private static final String AGENT_INSTRUMENTATION_METHOD = "instrumentation";
    private static final Object LOCK = new Object();

    private static volatile boolean probeComplete;
    private static volatile Instrumentation instrumentation;
    private static volatile KubeJsMixinRetransformTransformer transformer;

    private KubeJsMixinRetransform() {
    }

    /**
     * Resolves the agent-provided {@link Instrumentation} once per process, including a cached
     * "no agent" answer.
     *
     * @return the attached {@link Instrumentation}, or {@code null} when the agent is not installed
     */
    static Instrumentation instrumentation() {
        Instrumentation current = instrumentation;
        if (current != null || probeComplete) {
            return current;
        }
        synchronized (LOCK) {
            if (!probeComplete) {
                Instrumentation detected = detectAgent();
                instrumentation = detected;
                probeComplete = true;
                logProbeResult(detected);
            }
            return instrumentation;
        }
    }

    /**
     * @return {@code true} when the agent is attached and this JVM can retransform loaded classes
     */
    static boolean isAvailable() {
        Instrumentation current = instrumentation();
        return current != null && isRetransformClassesSupported(current);
    }

    /**
     * @return {@code true} when the loaded class may be handed to {@link Instrumentation#retransformClasses}
     */
    static boolean isModifiable(Class<?> target) {
        if (target == null) {
            return false;
        }
        Instrumentation current = instrumentation();
        return current != null && isModifiable(current, target);
    }

    /**
     * Loaded plus retransform-capable plus modifiable is the only combination that avoids the
     * historical fail-closed error. Every other combination lets the caller fall back to the
     * ModLauncher path.
     */
    static boolean canRetransform(Class<?> loaded) {
        return loaded != null && isModifiable(loaded);
    }

    /**
     * Named variant used at registration time so diagnostics can mention the script-visible target.
     */
    static boolean canRetransform(String targetClass, Class<?> loaded) {
        Objects.requireNonNull(targetClass, "KubeJS mixin target class cannot be null");
        return canRetransform(loaded);
    }

    /**
     * Explains why an already-loaded target cannot be handled by the retransformation path.
     *
     * @return a full sentence suitable for a fail-closed error message
     */
    static String retransformUnavailableReason(Class<?> loadedTarget) {
        Instrumentation current = instrumentation();
        if (current == null) {
            return "No TS Modify Java agent was detected; install the TS Modify agent "
                    + "(-javaagent:<path>/tsmodify-agent-<version>.jar) to retransform already-loaded classes.";
        }
        if (!isRetransformClassesSupported(current)) {
            return "The TS Modify Java agent is attached, but this JVM does not support retransforming "
                    + "already-loaded classes.";
        }
        if (loadedTarget == null) {
            return "The target class is no longer defined in the transforming class loader.";
        }
        if (!isModifiable(current, loadedTarget)) {
            return "The TS Modify Java agent is attached, but this class is not modifiable for "
                    + "retransformation; use a Java Sponge Mixin instead.";
        }
        return "Retransformation was not applied to this class.";
    }

    /**
     * Registers the retransformation transformer once and starts tracking the given binary names.
     *
     * <p>Must run before {@link #retransformAll(Class[])} so the callback is installed when the VM
     * asks for new bytecode.</p>
     *
     * @return {@code true} when every name is tracked
     */
    static boolean trackTargets(List<String> targetClasses) {
        Objects.requireNonNull(targetClasses, "KubeJS mixin retransformation targets cannot be null");
        if (targetClasses.isEmpty()) {
            return true;
        }
        Instrumentation current = instrumentation();
        if (current == null) {
            return false;
        }
        KubeJsMixinRetransformTransformer instance = transformer(current);
        if (instance == null) {
            return false;
        }
        for (String targetClass : targetClasses) {
            instance.track(targetClass);
        }
        return true;
    }

    /**
     * Retransforms one class.
     *
     * @return {@code true} when the VM accepted the replacement bytecode
     */
    static boolean retransform(Class<?> target) {
        return target != null && retransformAll(target);
    }

    /**
     * Retransforms the given classes in one VM operation, which needs a safepoint and therefore must
     * never be called while holding the mixin registry lock.
     *
     * <p>Every failure class ({@link UnmodifiableClassException}, {@link UnsupportedOperationException},
     * {@link InternalError}, injection errors) is logged at ERROR level and reported as {@code false}
     * so the caller can mark the targets as failed without crashing the process.</p>
     *
     * @return {@code true} when the VM accepted the replacement bytecode
     */
    static boolean retransformAll(Class<?>... targets) {
        if (targets == null || targets.length == 0) {
            return false;
        }
        Instrumentation current = instrumentation();
        if (current == null) {
            KubeJS.LOGGER.error(
                    "Cannot retransform {} already-loaded KubeJS mixin target(s): no TS Modify Java agent is attached",
                    targets.length);
            return false;
        }
        if (!isRetransformClassesSupported(current)) {
            KubeJS.LOGGER.error(
                    "Cannot retransform {} already-loaded KubeJS mixin target(s): this JVM does not support "
                            + "retransformation",
                    targets.length);
            return false;
        }
        for (Class<?> target : targets) {
            if (target == null) {
                KubeJS.LOGGER.error("Cannot retransform already-loaded KubeJS mixin targets: a target class is null");
                return false;
            }
        }
        try {
            current.retransformClasses(targets);
            return true;
        } catch (UnmodifiableClassException failure) {
            KubeJS.LOGGER.error(
                    "The JVM refused to retransform {} already-loaded KubeJS mixin target(s): {}",
                    targets.length,
                    describe(targets),
                    failure);
            return false;
        } catch (UnsupportedOperationException failure) {
            KubeJS.LOGGER.error(
                    "Retransformation is unsupported for {} already-loaded KubeJS mixin target(s): {}",
                    targets.length,
                    describe(targets),
                    failure);
            return false;
        } catch (InternalError failure) {
            KubeJS.LOGGER.error(
                    "An internal JVM error occurred while retransforming {} already-loaded KubeJS mixin target(s): {}",
                    targets.length,
                    describe(targets),
                    failure);
            return false;
        } catch (Throwable failure) {
            KubeJS.LOGGER.error(
                    "Retransformation failed for {} already-loaded KubeJS mixin target(s): {}",
                    targets.length,
                    describe(targets),
                    failure);
            return false;
        }
    }

    /**
     * Test seam: installs a fake {@link Instrumentation} so the retransformation paths can run on a
     * pure JVM. Passing {@code null} simulates "no agent attached" and also drops the cached
     * transformer.
     */
    static void setInstrumentationForTesting(Instrumentation candidate) {
        synchronized (LOCK) {
            instrumentation = candidate;
            probeComplete = true;
            transformer = null;
        }
    }

    private static Instrumentation detectAgent() {
        try {
            Class<?> agentClass = Class.forName(AGENT_CLASS_NAME, false, ClassLoader.getSystemClassLoader());
            Object candidate = agentClass.getMethod(AGENT_INSTRUMENTATION_METHOD).invoke(null);
            if (!(candidate instanceof Instrumentation detected)) {
                return null;
            }
            // S0-Q3 evidence: the holder class must come from the system class loader, because that
            // is the identity the reflection lookup above uses. Logging it makes the class-loader
            // contract observable instead of assumed.
            ClassLoader holderClassLoader = agentClass.getClassLoader();
            KubeJS.LOGGER.info(
                    "TS Modify Java agent holder class {} is loaded by {} (same as the system class loader: {})",
                    agentClass.getName(),
                    holderClassLoader,
                    holderClassLoader == ClassLoader.getSystemClassLoader());
            return detected;
        } catch (ClassNotFoundException notInstalled) {
            return null;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            KubeJS.LOGGER.warn(
                    "Probing the TS Modify Java agent failed; already-loaded KubeJS mixin targets stay fail-closed",
                    failure);
            return null;
        }
    }

    private static KubeJsMixinRetransformTransformer transformer(Instrumentation current) {
        KubeJsMixinRetransformTransformer existing = transformer;
        if (existing != null) {
            return existing;
        }
        synchronized (LOCK) {
            if (transformer == null) {
                KubeJsMixinRetransformTransformer created = new KubeJsMixinRetransformTransformer();
                try {
                    current.addTransformer(created, true);
                } catch (RuntimeException | LinkageError failure) {
                    KubeJS.LOGGER.error(
                            "Could not register the KubeJS mixin retransformation transformer; already-loaded "
                                    + "targets keep their previous bytecode",
                            failure);
                    return null;
                }
                transformer = created;
            }
            return transformer;
        }
    }

    private static void logProbeResult(Instrumentation detected) {
        if (detected == null) {
            KubeJS.LOGGER.info(
                    "No TS Modify Java agent detected; KubeJS mixin targets that are already loaded keep the "
                            + "fail-closed behavior. Add -javaagent:<path>/tsmodify-agent-<version>.jar to enable "
                            + "retransformation.");
            return;
        }
        KubeJS.LOGGER.info(
                "TS Modify Java agent detected (retransformSupported={}, redefineSupported={}); "
                        + "already-loaded targets are retransformed after FML load completion.",
                isRetransformClassesSupported(detected),
                isRedefineClassesSupported(detected));
    }

    private static boolean isRetransformClassesSupported(Instrumentation candidate) {
        try {
            return candidate.isRetransformClassesSupported();
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private static boolean isRedefineClassesSupported(Instrumentation candidate) {
        try {
            return candidate.isRedefineClassesSupported();
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private static boolean isModifiable(Instrumentation candidate, Class<?> target) {
        try {
            return candidate.isModifiableClass(target);
        } catch (RuntimeException failure) {
            KubeJS.LOGGER.debug(
                    "Could not determine whether {} is modifiable for retransformation",
                    target.getName(),
                    failure);
            return false;
        }
    }

    private static String describe(Class<?>[] targets) {
        StringBuilder description = new StringBuilder();
        for (Class<?> target : targets) {
            if (description.length() > 0) {
                description.append(", ");
            }
            description.append(target == null ? "<null>" : target.getName());
        }
        return description.toString();
    }
}
