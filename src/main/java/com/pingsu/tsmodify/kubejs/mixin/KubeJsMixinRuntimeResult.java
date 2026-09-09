package com.pingsu.tsmodify.kubejs.mixin;

/**
 * Result returned by a conditional replacement operation inserted into target bytecode.
 */
public final class KubeJsMixinRuntimeResult {
    private static final KubeJsMixinRuntimeResult NOT_APPLIED = new KubeJsMixinRuntimeResult(false, null);

    private final boolean applied;
    private final Object value;

    private KubeJsMixinRuntimeResult(boolean applied, Object value) {
        this.applied = applied;
        this.value = value;
    }

    static KubeJsMixinRuntimeResult notApplied() {
        return NOT_APPLIED;
    }

    static KubeJsMixinRuntimeResult applied(Object value) {
        return new KubeJsMixinRuntimeResult(true, value);
    }

    public boolean isApplied() {
        return applied;
    }

    public Object getValue() {
        return value;
    }
}
