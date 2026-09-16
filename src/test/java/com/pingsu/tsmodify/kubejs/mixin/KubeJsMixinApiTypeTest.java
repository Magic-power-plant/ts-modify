package com.pingsu.tsmodify.kubejs.mixin;

import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.Scriptable;
import dev.latvian.mods.rhino.Wrapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class KubeJsMixinApiTypeTest {
    private static final Set<String> CALLBACK_METHODS = Set.of(
            "before",
            "cancellableBefore",
            "after",
            "modifyArgs",
            "modifyReturnValue",
            "replace",
            "redirect",
            "modifyArg",
            "modifyVariable");

    @Test
    void publicMixinRegistrationMethodsExposeTheCallbackInterface() {
        int callbackMethodCount = 0;
        for (Method method : KubeJsMixinApi.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers()) || !CALLBACK_METHODS.contains(method.getName())) {
                continue;
            }
            Class<?>[] parameterTypes = method.getParameterTypes();
            callbackMethodCount++;
            assertEquals(
                    KubeJsMixinCallback.class,
                    parameterTypes[parameterTypes.length - 1],
                    method.toGenericString());
            assertFalse(
                    parameterTypes[parameterTypes.length - 1] == Object.class,
                    method.toGenericString());
        }
        assertEquals(12, callbackMethodCount);
    }

    @Test
    void rhinoConvertsScriptFunctionsForTypedCallbackParameters() {
        Context context = Context.enter();
        Scriptable scope = context.initStandardObjects();
        AtomicReference<KubeJsMixinInvocation> invocation = new AtomicReference<>();
        CallbackReceiver receiver = new CallbackReceiver(invocation);
        context.addToScope(scope, "receiver", receiver);

        Object seen = context.evaluateString(
                scope,
                "var seen = ''; receiver.accept(function(event) { seen = event.methodName; }); seen;",
                "kubejs-mixin-api-type-test.js",
                1,
                null);

        assertEquals("add", String.valueOf(Wrapper.unwrapped(seen)));
        assertEquals("add", invocation.get().getMethodName());
    }

    public static final class CallbackReceiver {
        private final AtomicReference<KubeJsMixinInvocation> invocation;

        private CallbackReceiver(AtomicReference<KubeJsMixinInvocation> invocation) {
            this.invocation = invocation;
        }

        public void accept(KubeJsMixinCallback callback) {
            KubeJsMixinInvocation event = new KubeJsMixinInvocation(
                    "com.example.Target",
                    "add",
                    "(I)V",
                    KubeJsMixinHook.BEFORE,
                    false,
                    null,
                    new Object[]{17},
                    null);
            invocation.set(event);
            callback.onInvoke(event);
        }
    }
}
