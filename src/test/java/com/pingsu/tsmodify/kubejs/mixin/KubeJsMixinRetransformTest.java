package com.pingsu.tsmodify.kubejs.mixin;

import com.example.tsmodifytest.KubeJsMixinHookTarget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-JVM coverage for the retransformation path. No real agent and no Minecraft runtime are
 * involved: the {@link Instrumentation} used by the flush cases is a dynamic proxy.
 */
class KubeJsMixinRetransformTest {
    private static final String TARGET_CLASS = KubeJsMixinHookTarget.class.getName();
    private static final String TARGET_INTERNAL_NAME = TARGET_CLASS.replace('.', '/');

    @BeforeEach
    void resetEnvironment() {
        KubeJsMixinRuleRegistry.clear();
        ModLauncherMixinTransformerInstaller.setLoadedTargetLookupForTesting(null);
        KubeJsMixinRetransform.setInstrumentationForTesting(null);
    }

    @AfterEach
    void clearEnvironment() {
        KubeJsMixinRuleRegistry.clear();
        ModLauncherMixinTransformerInstaller.setLoadedTargetLookupForTesting(null);
        KubeJsMixinRetransform.setInstrumentationForTesting(null);
    }

    @Test
    void transformerIgnoresLoadTimeCallbacks() throws IOException {
        KubeJsMixinRetransformTransformer transformer = new KubeJsMixinRetransformTransformer();
        transformer.track(TARGET_CLASS);
        KubeJsMixinRuleRegistry.registerForTesting(
                TARGET_CLASS,
                "add",
                "(I)V",
                KubeJsMixinHook.BEFORE,
                false,
                invocation -> {
                });

        byte[] bytecode = readTargetClassBytes();

        // A load-time callback (classBeingRedefined == null) belongs to the ModLauncher path.
        assertNull(transformer.transform(null, TARGET_INTERNAL_NAME, null, null, bytecode));
    }

    @Test
    void transformerIgnoresUntrackedClasses() throws IOException {
        KubeJsMixinRetransformTransformer transformer = new KubeJsMixinRetransformTransformer();
        KubeJsMixinRuleRegistry.registerForTesting(
                TARGET_CLASS,
                "add",
                "(I)V",
                KubeJsMixinHook.BEFORE,
                false,
                invocation -> {
                });

        // Rules exist for the target, but the class was never tracked: the registry must not be
        // consulted at all, so the original bytecode is preserved.
        assertNull(transformer.transform(
                null,
                TARGET_INTERNAL_NAME,
                KubeJsMixinHookTarget.class,
                null,
                readTargetClassBytes()));
    }

    @Test
    void transformerInjectsTrackedTargetBytesThatExecute() throws Exception {
        AtomicReference<KubeJsMixinInvocation> captured = new AtomicReference<>();
        KubeJsMixinRuleRegistry.registerForTesting(
                TARGET_CLASS,
                "add",
                "(I)V",
                KubeJsMixinHook.BEFORE,
                false,
                captured::set);

        byte[] transformed = retransformTarget();

        assertNotNull(transformed);
        Class<?> defined = defineTransformedTarget(transformed);
        Object target = defined.getDeclaredConstructor().newInstance();
        defined.getMethod("add", int.class).invoke(target, 11);

        assertEquals(11, captured.get().getArguments()[0]);
        assertEquals(target, captured.get().getReceiver());
        assertEquals(11, defined.getField("hits").getInt(target));
    }

    @Test
    void secondRetransformDoesNotDuplicateHooks() throws Exception {
        AtomicInteger callbacks = new AtomicInteger();
        KubeJsMixinRuleRegistry.registerForTesting(
                TARGET_CLASS,
                "add",
                "(I)V",
                KubeJsMixinHook.BEFORE,
                false,
                invocation -> callbacks.incrementAndGet());

        byte[] first = retransformTarget();
        assertNotNull(first);

        // The JVM hands a retransformation-capable transformer the class definition it currently
        // holds, which already contains the injected rule marker, so a second pass is a no-op.
        KubeJsMixinRetransformTransformer transformer = new KubeJsMixinRetransformTransformer();
        transformer.track(TARGET_CLASS);
        assertNull(transformer.transform(
                null,
                TARGET_INTERNAL_NAME,
                KubeJsMixinHookTarget.class,
                null,
                first));

        Class<?> defined = defineTransformedTarget(first);
        Object target = defined.getDeclaredConstructor().newInstance();
        defined.getMethod("add", int.class).invoke(target, 5);

        assertEquals(1, callbacks.get());
        assertEquals(5, defined.getField("hits").getInt(target));
    }

    @Test
    void canRetransformFallsBackWhenAgentMissing() {
        KubeJsMixinRetransform.setInstrumentationForTesting(null);

        assertNull(KubeJsMixinRetransform.instrumentation());
        assertFalse(KubeJsMixinRetransform.isAvailable());
        assertFalse(KubeJsMixinRetransform.isModifiable(KubeJsMixinHookTarget.class));
        assertFalse(KubeJsMixinRetransform.canRetransform(KubeJsMixinHookTarget.class));
        assertFalse(KubeJsMixinRetransform.canRetransform(TARGET_CLASS, KubeJsMixinHookTarget.class));
        assertFalse(KubeJsMixinRetransform.retransform(KubeJsMixinHookTarget.class));
        assertTrue(KubeJsMixinRetransform
                .retransformUnavailableReason(KubeJsMixinHookTarget.class)
                .contains("install the TS Modify agent"));
    }

    @Test
    void flushRetransformsEveryPendingTargetExactlyOnce() {
        List<Class<?>[]> retransformAttempts = new ArrayList<>();
        KubeJsMixinRetransform.setInstrumentationForTesting(
                fakeInstrumentation(retransformAttempts, false));
        ModLauncherMixinTransformerInstaller.setLoadedTargetLookupForTesting(
                name -> TARGET_CLASS.equals(name) ? KubeJsMixinHookTarget.class : null);

        registerPendingTarget("add", "(I)V");
        registerPendingTarget("mark", "()V");

        KubeJsMixinRuleRegistry.flushPendingRetransforms();
        // A second flush finds an empty pending set and must not touch the VM again.
        KubeJsMixinRuleRegistry.flushPendingRetransforms();

        assertEquals(1, retransformAttempts.size());
        assertArrayEquals(new Class<?>[]{KubeJsMixinHookTarget.class}, retransformAttempts.get(0));
        assertFalse(KubeJsMixinRuleRegistry.hasFailedRetransform(TARGET_CLASS));
        assertTrue(KubeJsMixinRuleRegistry.hasActiveRules(TARGET_CLASS));
    }

    @Test
    void failedRetransformDoesNotThrowAndLandsInFailedSet() {
        List<Class<?>[]> retransformAttempts = new ArrayList<>();
        KubeJsMixinRetransform.setInstrumentationForTesting(
                fakeInstrumentation(retransformAttempts, true));
        ModLauncherMixinTransformerInstaller.setLoadedTargetLookupForTesting(
                name -> TARGET_CLASS.equals(name) ? KubeJsMixinHookTarget.class : null);

        registerPendingTarget("add", "(I)V");

        assertDoesNotThrow(KubeJsMixinRuleRegistry::flushPendingRetransforms);
        assertEquals(1, retransformAttempts.size());
        assertTrue(KubeJsMixinRuleRegistry.hasFailedRetransform(TARGET_CLASS));
        assertTrue(KubeJsMixinRuleRegistry.hasActiveRules(TARGET_CLASS));

        // A failed target leaves the pending set, so it is not retransformed again.
        assertDoesNotThrow(KubeJsMixinRuleRegistry::flushPendingRetransforms);
        assertEquals(1, retransformAttempts.size());
    }

    @Test
    void injectorDoesNotAddMembersOrInvokeDynamic() throws IOException {
        KubeJsMixinRuleRegistry.registerForTesting(
                TARGET_CLASS,
                "add",
                "(I)V",
                KubeJsMixinHook.BEFORE,
                false,
                invocation -> {
                });

        ClassNode before = readTargetClass();
        ClassNode after = readTargetClass();
        int injectedRules = KubeJsMixinRuleRegistry.transform(TARGET_CLASS, after);

        assertEquals(1, injectedRules);
        assertEquals(0, KubeJsMixinRuleRegistry.transform(TARGET_CLASS, after));
        assertEquals(fieldSignatures(before), fieldSignatures(after));
        assertEquals(methodSignatures(before), methodSignatures(after));
        assertEquals(before.access, after.access);
        assertEquals(before.superName, after.superName);
        assertEquals(new HashSet<>(before.interfaces), new HashSet<>(after.interfaces));
        // KubeJsMixinHookTarget#objectPipeline uses string concatenation, which compiles to an
        // invokedynamic, so the guard is "the injection adds none" rather than "there are none".
        assertEquals(countInvokeDynamic(before), countInvokeDynamic(after));
    }

    private static byte[] retransformTarget() throws IOException {
        KubeJsMixinRetransformTransformer transformer = new KubeJsMixinRetransformTransformer();
        transformer.track(TARGET_CLASS);
        return transformer.transform(
                null,
                TARGET_INTERNAL_NAME,
                KubeJsMixinHookTarget.class,
                null,
                readTargetClassBytes());
    }

    private static void registerPendingTarget(String methodName, String methodDescriptor) {
        KubeJsMixinRuleRegistry.register(
                TARGET_CLASS,
                methodName,
                methodDescriptor,
                KubeJsMixinHook.BEFORE,
                false,
                "test",
                1,
                invocation -> {
                });
    }

    private static Instrumentation fakeInstrumentation(
            List<Class<?>[]> retransformAttempts,
            boolean failRetransformations) {
        InvocationHandler handler = (proxy, method, arguments) -> {
            switch (method.getName()) {
                case "isRetransformClassesSupported":
                case "isRedefineClassesSupported":
                case "isModifiableClass":
                    return true;
                case "addTransformer":
                    return null;
                case "retransformClasses":
                    retransformAttempts.add((Class<?>[]) arguments[0]);
                    if (failRetransformations) {
                        throw new UnmodifiableClassException("simulated retransformation failure");
                    }
                    return null;
                case "toString":
                    return "fake-instrumentation";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == arguments[0];
                default:
                    return defaultValue(method.getReturnType());
            }
        };
        return (Instrumentation) Proxy.newProxyInstance(
                KubeJsMixinRetransformTest.class.getClassLoader(),
                new Class<?>[]{Instrumentation.class},
                handler);
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive() || returnType == void.class) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == char.class) {
            return '\0';
        }
        if (returnType == byte.class) {
            return (byte) 0;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == float.class) {
            return 0F;
        }
        return 0D;
    }

    private static Set<String> fieldSignatures(ClassNode classNode) {
        Set<String> signatures = new HashSet<>();
        for (FieldNode field : classNode.fields) {
            signatures.add(field.name + ":" + field.desc);
        }
        return signatures;
    }

    private static Set<String> methodSignatures(ClassNode classNode) {
        Set<String> signatures = new HashSet<>();
        for (MethodNode method : classNode.methods) {
            signatures.add(method.name + method.desc);
        }
        return signatures;
    }

    private static int countInvokeDynamic(ClassNode classNode) {
        int count = 0;
        for (MethodNode method : classNode.methods) {
            if (method.instructions == null) {
                continue;
            }
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction.getOpcode() == Opcodes.INVOKEDYNAMIC) {
                    count++;
                }
            }
        }
        return count;
    }

    private static ClassNode readTargetClass() throws IOException {
        ClassNode classNode = new ClassNode();
        new ClassReader(readTargetClassBytes()).accept(classNode, 0);
        return classNode;
    }

    private static byte[] readTargetClassBytes() throws IOException {
        String resourceName = TARGET_INTERNAL_NAME + ".class";
        try (InputStream input = KubeJsMixinRetransformTest.class.getClassLoader().getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IOException("Missing test class resource " + resourceName);
            }
            return input.readAllBytes();
        }
    }

    private static Class<?> defineTransformedTarget(byte[] bytecode) {
        return new IsolatedClassLoader(KubeJsMixinRetransformTest.class.getClassLoader())
                .define(TARGET_CLASS, bytecode);
    }

    private static final class IsolatedClassLoader extends ClassLoader {
        private IsolatedClassLoader(ClassLoader parent) {
            super(parent);
        }

        private Class<?> define(String className, byte[] bytecode) {
            return defineClass(className, bytecode, 0, bytecode.length);
        }
    }
}
