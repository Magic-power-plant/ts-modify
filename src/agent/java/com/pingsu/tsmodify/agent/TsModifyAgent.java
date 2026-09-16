package com.pingsu.tsmodify.agent;

import java.lang.instrument.Instrumentation;

/**
 * Entry point of the optional TS Modify Java agent.
 *
 * <p>This class is packaged in its own jar ({@code tsmodify-agent-<version>.jar}) and must only
 * reference JDK types. It is loaded by the system class loader before the game starts, so it must
 * never touch Minecraft, Forge, Mixin, or TS Modify classes, and it must not rely on a logging
 * framework that has not been initialized yet.</p>
 *
 * <p>The mod side reads {@link #instrumentation()} reflectively through
 * {@code com.pingsu.tsmodify.kubejs.mixin.KubeJsMixinRetransform} and then uses the returned
 * {@link Instrumentation} directly. {@code Instrumentation} is defined by the platform class
 * loader, so the instance stays valid across class loaders.</p>
 */
public final class TsModifyAgent {
    private static volatile Instrumentation instrumentation;

    private TsModifyAgent() {
    }

    /**
     * Called by the JVM when the agent jar is passed through {@code -javaagent}.
     */
    public static void premain(String agentArgs, Instrumentation candidate) {
        install("premain", candidate);
    }

    /**
     * Called by the JVM when the agent jar is attached to an already running JVM.
     */
    public static void agentmain(String agentArgs, Instrumentation candidate) {
        install("agentmain", candidate);
    }

    /**
     * The only entry point the mod side reflects on.
     *
     * @return the attached {@link Instrumentation}, or {@code null} when the agent is not installed
     */
    public static Instrumentation instrumentation() {
        return instrumentation;
    }

    private static void install(String entryPoint, Instrumentation candidate) {
        try {
            if (candidate == null) {
                System.err.println("[tsmodify-agent] " + entryPoint
                        + ": Instrumentation is unavailable; retransformation of already-loaded classes is disabled");
                return;
            }
            instrumentation = candidate;
        } catch (Throwable failure) {
            System.err.println("[tsmodify-agent] " + entryPoint + " failed: " + failure);
            return;
        }
        try {
            System.err.println("[tsmodify-agent] attached through " + entryPoint
                    + ", retransformSupported=" + candidate.isRetransformClassesSupported()
                    + ", redefineSupported=" + candidate.isRedefineClassesSupported());
        } catch (Throwable failure) {
            System.err.println("[tsmodify-agent] attached through " + entryPoint
                    + ", but the capability probe failed: " + failure);
        }
    }
}
