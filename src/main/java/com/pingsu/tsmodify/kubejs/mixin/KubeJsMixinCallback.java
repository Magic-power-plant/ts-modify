package com.pingsu.tsmodify.kubejs.mixin;

/**
 * A capability implemented by a KubeJS function registered for a mixin hook.
 */
@FunctionalInterface
public interface KubeJsMixinCallback {
    void onInvoke(KubeJsMixinInvocation invocation);
}
