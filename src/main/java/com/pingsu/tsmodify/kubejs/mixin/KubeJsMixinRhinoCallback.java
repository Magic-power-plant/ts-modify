package com.pingsu.tsmodify.kubejs.mixin;

import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.Scriptable;

import java.util.Objects;

/**
 * Pins a startup-script callback to the Rhino context and scope that created it.
 */
final class KubeJsMixinRhinoCallback implements KubeJsMixinCallback {
    private final Context context;
    private final Scriptable scope;
    private final KubeJsMixinCallback delegate;

    private KubeJsMixinRhinoCallback(Context context, Scriptable scope, KubeJsMixinCallback delegate) {
        this.context = Objects.requireNonNull(context, "KubeJS mixin callback context cannot be null");
        this.scope = Objects.requireNonNull(scope, "KubeJS mixin callback scope cannot be null");
        this.delegate = Objects.requireNonNull(delegate, "KubeJS mixin callback cannot be null");
    }

    static KubeJsMixinCallback bind(Context context, Scriptable scope, KubeJsMixinCallback callback) {
        return new KubeJsMixinRhinoCallback(context, scope, callback);
    }

    static KubeJsMixinCallback bind(Context context, Scriptable scope, Object callback) {
        Objects.requireNonNull(callback, "KubeJS mixin callback cannot be null");
        KubeJsMixinCallback delegate = (KubeJsMixinCallback) Context.jsToJava(
                context,
                callback,
                KubeJsMixinCallback.class);
        return bind(context, scope, delegate);
    }

    @Override
    public void onInvoke(KubeJsMixinInvocation invocation) {
        Objects.requireNonNull(invocation, "KubeJS mixin invocation cannot be null");
        // Context.callSync serializes access to the retained Rhino runtime. The interface adapter
        // keeps the callback's original lexical scope; this outer call also pins its execution to
        // the context retained by this rule rather than a later ScriptManager reload context.
        context.callSync((ignoredContext, ignoredScope, ignoredThis, arguments) -> {
            delegate.onInvoke((KubeJsMixinInvocation) arguments[0]);
            return null;
        }, scope, scope, new Object[]{invocation});
    }
}
