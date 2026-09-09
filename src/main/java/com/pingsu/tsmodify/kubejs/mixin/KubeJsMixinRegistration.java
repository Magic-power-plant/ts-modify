package com.pingsu.tsmodify.kubejs.mixin;

/**
 * Handle returned to KubeJS when a hook is accepted.
 */
public final class KubeJsMixinRegistration {
    private final AbstractKubeJsMixinRule rule;

    KubeJsMixinRegistration(AbstractKubeJsMixinRule rule) {
        this.rule = rule;
    }

    public String getId() {
        return rule.id();
    }

    public String getState() {
        return rule.stateName();
    }

    public boolean isActive() {
        return rule.isActive();
    }

    public boolean disable() {
        return rule.disable();
    }
}
