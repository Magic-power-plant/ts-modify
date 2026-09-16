package com.pingsu.tsmodify.kubejs.mixin;

/**
 * A capability implemented by a KubeJS function registered for a mixin hook.
 *
 * <p>This public functional interface is also the callback type exposed by the mixin API so
 * ProbeJS can export the invocation event instead of falling back to {@code Object}.</p>
 */
@FunctionalInterface
public interface KubeJsMixinCallback {
    void onInvoke(KubeJsMixinInvocation invocation);
}
