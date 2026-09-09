package com.pingsu.tsmodify.kubejs.mixin;

final class KubeJsMixinScriptRule extends AbstractKubeJsMixinRule {
    private final KubeJsMixinCallback callback;

    KubeJsMixinScriptRule(
            String id,
            String targetClass,
            String methodName,
            String methodDescriptor,
            KubeJsMixinHook hook,
            boolean cancellable,
            KubeJsMixinInstructionTarget instructionTarget,
            String source,
            int line,
            KubeJsMixinCallback callback) {
        super(id, targetClass, methodName, methodDescriptor, hook, cancellable, instructionTarget, source, line);
        this.callback = callback;
    }

    @Override
    protected void invokeCallback(KubeJsMixinInvocation invocation) {
        callback.onInvoke(invocation);
    }
}
