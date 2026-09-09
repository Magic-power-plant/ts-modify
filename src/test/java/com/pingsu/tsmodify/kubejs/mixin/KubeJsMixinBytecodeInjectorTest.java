package com.pingsu.tsmodify.kubejs.mixin;

import com.example.tsmodifytest.KubeJsMixinHookTarget;
import cpw.mods.modlauncher.TransformStore;
import cpw.mods.modlauncher.TransformTargetLabel;
import cpw.mods.modlauncher.TransformerHolder;
import cpw.mods.modlauncher.api.ITransformer;
import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.Scriptable;
import dev.latvian.mods.rhino.Wrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KubeJsMixinBytecodeInjectorTest {
    private static final String TARGET_CLASS = KubeJsMixinHookTarget.class.getName();

    @BeforeEach
    void clearRulesBeforeTest() {
        KubeJsMixinRuleRegistry.clear();
    }

    @AfterEach
    void clearRulesAfterTest() {
        KubeJsMixinRuleRegistry.clear();
    }

    @Test
    void beforeHookReceivesReceiverAndBoxedArgumentsInDefinedClass() throws Exception {
        AtomicReference<KubeJsMixinInvocation> captured = new AtomicReference<>();
        KubeJsMixinRuleRegistry.registerForTesting(
                TARGET_CLASS,
                "add",
                "(I)V",
                KubeJsMixinHook.BEFORE,
                false,
                captured::set);

        Class<?> transformed = defineTransformedTarget();
        Object target = transformed.getDeclaredConstructor().newInstance();
        transformed.getMethod("add", int.class).invoke(target, 7);

        KubeJsMixinInvocation invocation = captured.get();
        assertEquals(TARGET_CLASS, invocation.getTargetClass());
        assertEquals("before", invocation.getHook());
        assertEquals(target, invocation.getReceiver());
        assertArrayEquals(new Object[]{7}, invocation.getArguments());
        assertEquals(7, transformed.getField("hits").getInt(target));
    }

    @Test
    void afterHookObservesReturnValueWithoutChangingIt() throws Exception {
        AtomicReference<KubeJsMixinInvocation> captured = new AtomicReference<>();
        KubeJsMixinRuleRegistry.registerForTesting(
                TARGET_CLASS,
                "sum",
                "(II)I",
                KubeJsMixinHook.AFTER,
                false,
                captured::set);

        Class<?> transformed = defineTransformedTarget();
        Object target = transformed.getDeclaredConstructor().newInstance();

        assertEquals(9, transformed.getMethod("sum", int.class, int.class).invoke(target, 4, 5));
        KubeJsMixinInvocation invocation = captured.get();
        assertEquals("after", invocation.getHook());
        assertEquals(9, invocation.getReturnValue());
        assertArrayEquals(new Object[]{4, 5}, invocation.getArguments());
    }

    @Test
    void staticAndWideMethodsExposeCorrectReceiverAndBoxedValues() throws Exception {
        AtomicReference<KubeJsMixinInvocation> beforeInvocation = new AtomicReference<>();
        AtomicReference<KubeJsMixinInvocation> afterInvocation = new AtomicReference<>();
        KubeJsMixinRuleRegistry.registerForTesting(
                TARGET_CLASS,
                "staticDescribe",
                "(JDLjava/lang/Object;)Ljava/lang/String;",
                KubeJsMixinHook.BEFORE,
                false,
                beforeInvocation::set);
        KubeJsMixinRuleRegistry.registerForTesting(
                TARGET_CLASS,
                "wideSum",
                "(JJ)J",
                KubeJsMixinHook.AFTER,
                false,
                afterInvocation::set);

        Class<?> transformed = defineTransformedTarget();
        Object target = transformed.getDeclaredConstructor().newInstance();
        assertEquals(
                "marker:7.5",
                transformed.getMethod("staticDescribe", long.class, double.class, Object.class)
                        .invoke(null, 5L, 1.5D, "marker"));
        assertEquals(12L, transformed.getMethod("wideSum", long.class, long.class).invoke(target, 5L, 7L));

        assertNull(beforeInvocation.get().getReceiver());
        assertArrayEquals(new Object[]{5L, 1.5D, "marker"}, beforeInvocation.get().getArguments());
        assertEquals(12L, afterInvocation.get().getReturnValue());
        assertArrayEquals(new Object[]{5L, 7L}, afterInvocation.get().getArguments());
    }

    @Test
    void afterHookRunsAtEveryNormalReturnPathAndVoidReturnsNull() throws Exception {
        AtomicInteger multipleReturnCalls = new AtomicInteger();
        AtomicReference<KubeJsMixinInvocation> voidInvocation = new AtomicReference<>();
        KubeJsMixinRuleRegistry.registerForTesting(
                TARGET_CLASS,
                "multipleReturns",
                "(Z)V",
                KubeJsMixinHook.AFTER,
                false,
                invocation -> multipleReturnCalls.incrementAndGet());
        KubeJsMixinRuleRegistry.registerForTesting(
                TARGET_CLASS,
                "afterVoid",
                "()V",
                KubeJsMixinHook.AFTER,
                false,
                voidInvocation::set);

        Class<?> transformed = defineTransformedTarget();
        Object target = transformed.getDeclaredConstructor().newInstance();
        transformed.getMethod("multipleReturns", boolean.class).invoke(target, true);
        transformed.getMethod("multipleReturns", boolean.class).invoke(target, false);
        transformed.getMethod("afterVoid").invoke(target);

        assertEquals(2, multipleReturnCalls.get());
        assertNull(voidInvocation.get().getReturnValue());
    }

    @Test
    void transformIsIdempotentForTheSameRule() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        KubeJsMixinRuleRegistry.registerForTesting(
                TARGET_CLASS,
                "add",
                "(I)V",
                KubeJsMixinHook.BEFORE,
                false,
                invocation -> calls.incrementAndGet());

        ClassNode classNode = readTargetClass();
        KubeJsMixinRuleRegistry.transform(TARGET_CLASS, classNode);
        KubeJsMixinRuleRegistry.transform(TARGET_CLASS, classNode);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        classNode.accept(writer);
        Class<?> transformed = defineTransformedTarget(writer.toByteArray());
        Object target = transformed.getDeclaredConstructor().newInstance();
        transformed.getMethod("add", int.class).invoke(target, 3);

        assertEquals(1, calls.get());
        assertEquals(3, transformed.getField("hits").getInt(target));
    }

    @Test
    void disabledRuleIsNotInjected() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        KubeJsMixinRegistration registration = KubeJsMixinRuleRegistry.registerForTesting(
                TARGET_CLASS,
                "add",
                "(I)V",
                KubeJsMixinHook.BEFORE,
                false,
                invocation -> calls.incrementAndGet());
        assertTrue(registration.disable());
        assertFalse(registration.disable());

        Class<?> transformed = defineTransformedTarget();
        Object target = transformed.getDeclaredConstructor().newInstance();
        transformed.getMethod("add", int.class).invoke(target, 3);

        assertEquals(0, calls.get());
        assertEquals("disabled", registration.getState());
    }

    @Test
    void invocationArgumentsAreDefensiveCopies() {
        Object[] original = {1};
        KubeJsMixinInvocation invocation = new KubeJsMixinInvocation(
                TARGET_CLASS,
                "add",
                "(I)V",
                KubeJsMixinHook.BEFORE,
                false,
                null,
                original,
                null);

        original[0] = 2;
        Object[] returned = invocation.getArguments();
        returned[0] = 3;

        assertArrayEquals(new Object[]{1}, invocation.getArguments());
    }

    @Test
    void modifyArgsWritesConvertedValuesBackToMethodParameterSlots() throws Exception {
        AtomicReference<KubeJsMixinInvocation> captured = new AtomicReference<>();
        KubeJsMixinRuleRegistry.registerForTesting(
                TARGET_CLASS,
                "sum",
                "(II)I",
                KubeJsMixinHook.MODIFY_ARGS,
                false,
                invocation -> {
                    invocation.setArgument(0, 10D);
                    invocation.setArgument(1, 32D);
                    captured.set(invocation);
                });

        Class<?> transformed = defineTransformedTarget();
        Object target = transformed.getDeclaredConstructor().newInstance();

        assertEquals(42, transformed.getMethod("sum", int.class, int.class).invoke(target, 1, 2));
        assertArrayEquals(new Object[]{10, 32}, captured.get().getArguments());
    }

    @Test
    void modifyReturnValueChangesEveryNonVoidReturn() throws Exception {
        KubeJsMixinRuleRegistry.registerForTesting(
                TARGET_CLASS,
                "sum",
                "(II)I",
                KubeJsMixinHook.MODIFY_RETURN_VALUE,
                false,
                invocation -> invocation.setReturnValue(73D));

        Class<?> transformed = defineTransformedTarget();
        Object target = transformed.getDeclaredConstructor().newInstance();

        assertEquals(73, transformed.getMethod("sum", int.class, int.class).invoke(target, 4, 5));
    }

    @Test
    void cancellableBeforeCanReturnAValueFromANonVoidMethod() throws Exception {
        KubeJsMixinRuleRegistry.registerForTesting(
                TARGET_CLASS,
                "sum",
                "(II)I",
                KubeJsMixinHook.BEFORE,
                true,
                invocation -> {
                    invocation.setReturnValue(91);
                    invocation.cancel();
                });

        Class<?> transformed = defineTransformedTarget();
        Object target = transformed.getDeclaredConstructor().newInstance();

        assertEquals(91, transformed.getMethod("sum", int.class, int.class).invoke(target, 4, 5));
    }

    @Test
    void replaceSkipsTheOriginalBodyForVoidAndNonVoidMethods() throws Exception {
        AtomicInteger replacementCalls = new AtomicInteger();
        KubeJsMixinRuleRegistry.registerForTesting(
                TARGET_CLASS,
                "sum",
                "(II)I",
                KubeJsMixinHook.REPLACE,
                false,
                invocation -> {
                    replacementCalls.incrementAndGet();
                    invocation.setReturnValue(101);
                });
        KubeJsMixinRuleRegistry.registerForTesting(
                TARGET_CLASS,
                "mark",
                "()V",
                KubeJsMixinHook.REPLACE,
                false,
                invocation -> replacementCalls.incrementAndGet());

        Class<?> transformed = defineTransformedTarget();
        Object target = transformed.getDeclaredConstructor().newInstance();

        assertEquals(101, transformed.getMethod("sum", int.class, int.class).invoke(target, 4, 5));
        transformed.getMethod("mark").invoke(target);
        assertEquals(2, replacementCalls.get());
        assertEquals(0, transformed.getField("hits").getInt(target));
    }

    @Test
    void incompleteNonVoidReplacementDisablesTheRuleAndFallsBackToTheOriginalBody() throws Exception {
        KubeJsMixinRegistration registration = KubeJsMixinRuleRegistry.registerForTesting(
                TARGET_CLASS,
                "sum",
                "(II)I",
                KubeJsMixinHook.REPLACE,
                false,
                invocation -> {
                });

        Class<?> transformed = defineTransformedTarget();
        Object target = transformed.getDeclaredConstructor().newInstance();

        assertEquals(9, transformed.getMethod("sum", int.class, int.class).invoke(target, 4, 5));
        assertEquals("disabled", registration.getState());
    }

    @Test
    void invalidPrimitiveMutationDisablesTheRuleAndKeepsTheOriginalValue() throws Exception {
        KubeJsMixinRegistration registration = KubeJsMixinRuleRegistry.registerForTesting(
                TARGET_CLASS,
                "sum",
                "(II)I",
                KubeJsMixinHook.MODIFY_ARGS,
                false,
                invocation -> invocation.setArgument(0, "not-a-number"));

        Class<?> transformed = defineTransformedTarget();
        Object target = transformed.getDeclaredConstructor().newInstance();

        assertEquals(9, transformed.getMethod("sum", int.class, int.class).invoke(target, 4, 5));
        assertEquals("disabled", registration.getState());
    }

    @Test
    void redirectReplacesAnExactInvocationAndExposesItsOperands() throws Exception {
        AtomicReference<KubeJsMixinInvocation> captured = new AtomicReference<>();
        KubeJsMixinRuleRegistry.registerForTesting(
                TARGET_CLASS,
                "redirectTarget",
                "(I)I",
                KubeJsMixinHook.REDIRECT,
                false,
                new KubeJsMixinInstructionTarget.Invoke(
                        TARGET_CLASS.replace('.', '/'),
                        "staticDouble",
                        "(I)I",
                        0,
                        -1),
                invocation -> {
                    captured.set(invocation);
                    invocation.setReturnValue(123D);
                });

        Class<?> transformed = defineTransformedTarget();
        Object target = transformed.getDeclaredConstructor().newInstance();

        assertEquals(123, transformed.getMethod("redirectTarget", int.class).invoke(target, 7));
        assertNull(captured.get().getInstructionReceiver());
        assertArrayEquals(new Object[]{7}, captured.get().getInstructionArguments());
        assertEquals(0, transformed.getField("staticHelperCalls").getInt(null));
    }

    @Test
    void modifyArgRewritesOneSelectedInvocationOperand() throws Exception {
        AtomicReference<KubeJsMixinInvocation> captured = new AtomicReference<>();
        AtomicReference<Object> originalValue = new AtomicReference<>();
        KubeJsMixinRuleRegistry.registerForTesting(
                TARGET_CLASS,
                "modifyInvocationArgument",
                "(II)I",
                KubeJsMixinHook.MODIFY_ARG,
                false,
                new KubeJsMixinInstructionTarget.Invoke(
                        "java/lang/Math",
                        "max",
                        "(II)I",
                        0,
                        1),
                invocation -> {
                    captured.set(invocation);
                    originalValue.set(invocation.getValue());
                    invocation.setValue(50D);
                });

        Class<?> transformed = defineTransformedTarget();
        Object target = transformed.getDeclaredConstructor().newInstance();

        assertEquals(50, transformed.getMethod("modifyInvocationArgument", int.class, int.class).invoke(target, 2, 4));
        assertEquals(4, originalValue.get());
        assertEquals(50, captured.get().getValue());
        assertArrayEquals(new Object[]{2, 4}, captured.get().getInstructionArguments());
    }

    @Test
    void modifyVariableRewritesLocalValuesAtLoadAndStoreInstructions() throws Exception {
        KubeJsMixinRuleRegistry.registerForTesting(
                TARGET_CLASS,
                "variableFlow",
                "(I)I",
                KubeJsMixinHook.MODIFY_VARIABLE,
                false,
                new KubeJsMixinInstructionTarget.Variable(
                        2,
                        "I",
                        KubeJsMixinInstructionTarget.VariableAccess.STORE,
                        0),
                invocation -> invocation.setVariableValue(7D));

        Class<?> storeTransformed = defineTransformedTarget();
        Object storeTarget = storeTransformed.getDeclaredConstructor().newInstance();
        assertEquals(14, storeTransformed.getMethod("variableFlow", int.class).invoke(storeTarget, 3));

        KubeJsMixinRuleRegistry.clear();
        KubeJsMixinRuleRegistry.registerForTesting(
                TARGET_CLASS,
                "variableFlow",
                "(I)I",
                KubeJsMixinHook.MODIFY_VARIABLE,
                false,
                new KubeJsMixinInstructionTarget.Variable(
                        2,
                        "I",
                        KubeJsMixinInstructionTarget.VariableAccess.LOAD,
                        0),
                invocation -> invocation.setVariableValue(11D));

        Class<?> loadTransformed = defineTransformedTarget();
        Object loadTarget = loadTransformed.getDeclaredConstructor().newInstance();
        assertEquals(22, loadTransformed.getMethod("variableFlow", int.class).invoke(loadTarget, 3));
    }

    @Test
    void writableRulesHandleWideStaticAndReferenceSlots() throws Exception {
        KubeJsMixinRuleRegistry.registerForTesting(
                TARGET_CLASS,
                "staticDescribe",
                "(JDLjava/lang/Object;)Ljava/lang/String;",
                KubeJsMixinHook.MODIFY_ARGS,
                false,
                invocation -> {
                    invocation.setArgument(0, 8D);
                    invocation.setArgument(1, 2.5D);
                    invocation.setArgument(2, "rewritten");
                });
        KubeJsMixinRuleRegistry.registerForTesting(
                TARGET_CLASS,
                "echo",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                KubeJsMixinHook.REPLACE,
                false,
                invocation -> invocation.setReturnValue("replacement"));
        KubeJsMixinRuleRegistry.registerForTesting(
                TARGET_CLASS,
                "echo",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                KubeJsMixinHook.MODIFY_RETURN_VALUE,
                false,
                invocation -> invocation.setReturnValue(invocation.getReturnValue() + "!"));
        KubeJsMixinRuleRegistry.registerForTesting(
                TARGET_CLASS,
                "objectPipeline",
                "(Ljava/lang/String;)Ljava/lang/String;",
                KubeJsMixinHook.MODIFY_VARIABLE,
                false,
                new KubeJsMixinInstructionTarget.Variable(
                        2,
                        "Ljava/lang/String;",
                        KubeJsMixinInstructionTarget.VariableAccess.LOAD,
                        0),
                invocation -> invocation.setVariableValue("changed"));

        Class<?> transformed = defineTransformedTarget();
        Object target = transformed.getDeclaredConstructor().newInstance();

        assertEquals(
                "rewritten:20.0",
                transformed.getMethod("staticDescribe", long.class, double.class, Object.class)
                        .invoke(null, 1L, 1.5D, "original"));
        assertEquals("replacement!", transformed.getMethod("echo", Object.class).invoke(target, "original"));
        assertEquals("CHANGED", transformed.getMethod("objectPipeline", String.class).invoke(target, "ignored"));
    }

    @Test
    void cancellableBeforeHookCanStillSkipVoidMethodBody() throws Exception {
        KubeJsMixinRuleRegistry.registerForTesting(
                TARGET_CLASS,
                "mark",
                "()V",
                KubeJsMixinHook.BEFORE,
                true,
                KubeJsMixinInvocation::cancel);

        Class<?> transformed = defineTransformedTarget();
        Object target = transformed.getDeclaredConstructor().newInstance();
        transformed.getMethod("mark").invoke(target);

        assertEquals(0, transformed.getField("hits").getInt(target));
    }

    @Test
    void classTransformerAppliesRulesToTheTargetClassNode() throws Exception {
        AtomicReference<KubeJsMixinInvocation> captured = new AtomicReference<>();
        KubeJsMixinRuleRegistry.registerForTesting(
                TARGET_CLASS,
                "add",
                "(I)V",
                KubeJsMixinHook.BEFORE,
                false,
                captured::set);

        ClassNode classNode = readTargetClass();
        KubeJsMixinClassTransformer transformer = new KubeJsMixinClassTransformer(TARGET_CLASS);
        transformer.transform(classNode, null);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        classNode.accept(writer);
        Class<?> transformed = defineTransformedTarget(writer.toByteArray());
        Object target = transformed.getDeclaredConstructor().newInstance();
        transformed.getMethod("add", int.class).invoke(target, 23);

        assertEquals(23, captured.get().getArguments()[0]);
        assertEquals(23, transformed.getField("hits").getInt(target));
    }

    @Test
    void installerRegistersWrappedTransformerInPinnedModLauncherStore() throws Exception {
        AtomicReference<KubeJsMixinInvocation> captured = new AtomicReference<>();
        KubeJsMixinRuleRegistry.registerForTesting(
                TARGET_CLASS,
                "add",
                "(I)V",
                KubeJsMixinHook.BEFORE,
                false,
                captured::set);

        TransformStore store = new TransformStore();
        ModLauncherMixinTransformerInstaller.installIntoStore(store, TARGET_CLASS);

        Method needsTransforming = TransformStore.class.getDeclaredMethod("needsTransforming", String.class);
        Method transformersFor = TransformStore.class.getDeclaredMethod(
                "getTransformersFor",
                String.class,
                TransformTargetLabel.LabelType.class);
        assertTrue(needsTransforming.trySetAccessible());
        assertTrue(transformersFor.trySetAccessible());
        assertTrue((boolean) needsTransforming.invoke(store, TARGET_CLASS.replace('.', '/')));

        @SuppressWarnings("unchecked")
        List<ITransformer<ClassNode>> transformers = (List<ITransformer<ClassNode>>) transformersFor.invoke(
                store,
                TARGET_CLASS,
                TransformTargetLabel.LabelType.CLASS);
        assertEquals(1, transformers.size());
        ITransformer<ClassNode> transformer = transformers.get(0);
        assertTrue(transformer instanceof TransformerHolder<?>);

        ClassNode classNode = readTargetClass();
        transformer.transform(classNode, null);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        classNode.accept(writer);
        Class<?> transformed = defineTransformedTarget(writer.toByteArray());
        Object target = transformed.getDeclaredConstructor().newInstance();
        transformed.getMethod("add", int.class).invoke(target, 29);

        assertEquals(29, captured.get().getArguments()[0]);
        assertEquals(29, transformed.getField("hits").getInt(target));
    }

    @Test
    void installerDoesNotDuplicateItsTransformerForOneTarget() throws Exception {
        KubeJsMixinRuleRegistry.registerForTesting(
                TARGET_CLASS,
                "add",
                "(I)V",
                KubeJsMixinHook.BEFORE,
                false,
                invocation -> {
                });

        TransformStore store = new TransformStore();
        ModLauncherMixinTransformerInstaller.installIntoStore(store, TARGET_CLASS);
        ModLauncherMixinTransformerInstaller.installIntoStore(store, TARGET_CLASS);

        Method transformersFor = TransformStore.class.getDeclaredMethod(
                "getTransformersFor",
                String.class,
                TransformTargetLabel.LabelType.class);
        assertTrue(transformersFor.trySetAccessible());
        @SuppressWarnings("unchecked")
        List<ITransformer<ClassNode>> transformers = (List<ITransformer<ClassNode>>) transformersFor.invoke(
                store,
                TARGET_CLASS,
                TransformTargetLabel.LabelType.CLASS);
        assertEquals(1, transformers.size());
    }

    @Test
    void failedNormalRegistrationRollsBackTheRule() {
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> KubeJsMixinRuleRegistry.register(
                TARGET_CLASS,
                "add",
                "(I)V",
                KubeJsMixinHook.BEFORE,
                false,
                "test",
                1,
                invocation -> {
                }));

        assertTrue(exception.getMessage().contains("ModLauncher"));
        assertFalse(KubeJsMixinRuleRegistry.hasActiveRules(TARGET_CLASS));
    }

    @Test
    void callbackFailureDisablesOnlyTheFailingRule() throws Exception {
        KubeJsMixinRegistration registration = KubeJsMixinRuleRegistry.registerForTesting(
                TARGET_CLASS,
                "mark",
                "()V",
                KubeJsMixinHook.BEFORE,
                false,
                invocation -> {
                    throw new IllegalStateException("test callback failure");
                });

        Class<?> transformed = defineTransformedTarget();
        Object target = transformed.getDeclaredConstructor().newInstance();

        assertDoesNotThrow(() -> invoke(transformed.getMethod("mark"), target));
        assertDoesNotThrow(() -> invoke(transformed.getMethod("mark"), target));
        assertEquals("disabled", registration.getState());
        assertFalse(registration.isActive());
        assertEquals(2, transformed.getField("hits").getInt(target));
    }

    @Test
    void invalidTargetsAndInstructionRulesAreRejectedBeforeTransformation() {
        assertThrows(IllegalArgumentException.class, () -> KubeJsMixinRuleRegistry.registerForTesting(
                "java.lang.String",
                "valueOf",
                "(I)Ljava/lang/String;",
                KubeJsMixinHook.BEFORE,
                false,
                invocation -> {
                }));
        assertThrows(IllegalArgumentException.class, () -> KubeJsMixinRuleRegistry.registerForTesting(
                TARGET_CLASS,
                "afterVoid",
                "()V",
                KubeJsMixinHook.MODIFY_RETURN_VALUE,
                false,
                invocation -> {
                }));
        assertThrows(IllegalArgumentException.class, () -> KubeJsMixinRuleRegistry.registerForTesting(
                TARGET_CLASS,
                "modifyInvocationArgument",
                "(II)I",
                KubeJsMixinHook.MODIFY_ARG,
                false,
                new KubeJsMixinInstructionTarget.Invoke("java/lang/Math", "max", "(II)I", 0, 2),
                invocation -> {
                }));
        assertThrows(IllegalArgumentException.class, () -> KubeJsMixinRuleRegistry.registerForTesting(
                TARGET_CLASS,
                "variableFlow",
                "(I)I",
                KubeJsMixinHook.MODIFY_VARIABLE,
                false,
                new KubeJsMixinInstructionTarget.Variable(
                        2,
                        "()V",
                        KubeJsMixinInstructionTarget.VariableAccess.LOAD,
                        0),
                invocation -> {
                }));
        assertThrows(IllegalArgumentException.class, () -> KubeJsMixinRuleRegistry.registerForTesting(
                TARGET_CLASS,
                "add",
                null,
                KubeJsMixinHook.BEFORE,
                false,
                invocation -> {
                }));
        assertTrue(KubeJsMixinTargetPolicy.isProtected("net.minecraft.server.MinecraftServer"));
        assertTrue(KubeJsMixinTargetPolicy.isProtected("net.minecraft.server.MinecraftServer$Worker"));
        assertTrue(KubeJsMixinTargetPolicy.isProtected("net.minecraft.server.dedicated.DedicatedServer"));
        assertTrue(KubeJsMixinTargetPolicy.isProtected(KubeJsMixinBytecodeInjectorTest.class.getName()));
        assertFalse(KubeJsMixinTargetPolicy.isProtected(TARGET_CLASS));
    }

    @Test
    void classTransformerRejectsAnUnexpectedClassNode() {
        KubeJsMixinClassTransformer transformer = new KubeJsMixinClassTransformer(TARGET_CLASS);
        ClassNode unexpected = new ClassNode();
        unexpected.name = "com/example/Other";

        assertThrows(
                KubeJsMixinTransformationException.class,
                () -> transformer.transform(unexpected, null));
    }

    @Test
    void registryRejectsAnUnexpectedClassNode() {
        KubeJsMixinRuleRegistry.registerForTesting(
                TARGET_CLASS,
                "add",
                "(I)V",
                KubeJsMixinHook.BEFORE,
                false,
                invocation -> {
                });

        ClassNode unexpected = new ClassNode();
        unexpected.name = "com/example/Other";

        assertThrows(
                KubeJsMixinTransformationException.class,
                () -> KubeJsMixinRuleRegistry.transform(TARGET_CLASS, unexpected));
    }

    @Test
    void rhinoFunctionAdaptsToKubeJsMixinCallbackAndReadsInvocationProperties() throws InterruptedException {
        Context context = Context.enter();
        Scriptable scope = context.initStandardObjects();
        Object callback = context.evaluateString(
                scope,
                "var observed = null; function callback(event) { observed = event.arguments[0]; } callback;",
                "kubejs-mixin-callback-test.js",
                1,
                null);
        context.setTopCall(scope);
        KubeJsMixinCallback handler;
        try {
            handler = (KubeJsMixinCallback) Context.jsToJava(
                    context,
                    callback,
                    KubeJsMixinCallback.class);
        } finally {
            context.setTopCall(null);
        }

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread callbackThread = new Thread(() -> {
            try {
                handler.onInvoke(new KubeJsMixinInvocation(
                        TARGET_CLASS,
                        "add",
                        "(I)V",
                        KubeJsMixinHook.BEFORE,
                        false,
                        null,
                        new Object[]{17},
                        null));
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        }, "kubejs-mixin-callback-test");
        callbackThread.start();
        callbackThread.join();

        assertNull(failure.get());
        assertEquals(17, ((Number) Wrapper.unwrapped(scope.get(context, "observed", scope))).intValue());
    }

    @Test
    void rhinoCallbacksCanModifyArgumentsAndNonVoidReturnValues() throws Exception {
        Context context = Context.enter();
        Scriptable scope = context.initStandardObjects();
        KubeJsMixinCallback argumentCallback = createRhinoCallback(context, scope, """
                function(event) {
                    event.setArguments([Number(event.arguments[0]) + 4, event.arguments[1]]);
                }
                """);
        KubeJsMixinCallback returnCallback = createRhinoCallback(context, scope, """
                function(event) {
                    event.setReturnValue(Number(event.returnValue) + 1);
                }
                """);
        KubeJsMixinRuleRegistry.registerForTesting(
                TARGET_CLASS,
                "sum",
                "(II)I",
                KubeJsMixinHook.MODIFY_ARGS,
                false,
                argumentCallback);
        KubeJsMixinRuleRegistry.registerForTesting(
                TARGET_CLASS,
                "sum",
                "(II)I",
                KubeJsMixinHook.MODIFY_RETURN_VALUE,
                false,
                returnCallback);

        Class<?> transformed = defineTransformedTarget();
        Object target = transformed.getDeclaredConstructor().newInstance();

        assertEquals(10, transformed.getMethod("sum", int.class, int.class).invoke(target, 2, 3));
    }

    @Test
    void rhinoCallbacksComposeCancellableBeforeAndAfterHooksForComplexBatchFlow() throws Exception {
        Context context = Context.enter();
        Scriptable scope = context.initStandardObjects();
        context.evaluateString(scope, "var observed = [];", "kubejs-mixin-complex-test.js", 1, null);
        KubeJsMixinCallback auditCallback = createRhinoCallback(context, scope, """
                function(event) {
                    observed.push('before:' + event.methodName + ':' + String(event.arguments[0])
                            + ':' + Number(event.arguments[1]) + ':' + String(event.arguments[2]));
                }
                """);
        KubeJsMixinCallback cancellationCallback = createRhinoCallback(context, scope, """
                function(event) {
                    if (String(event.arguments[0]) === 'blocked' && Number(event.arguments[1]) > 10) {
                        event.cancel();
                        observed.push('cancelled:' + String(event.arguments[0]));
                    }
                }
                """);
        KubeJsMixinCallback resultCallback = createRhinoCallback(context, scope, """
                function(event) {
                    observed.push('after:' + event.methodName + ':' + Number(event.returnValue)
                            + ':' + String(event.arguments[2]));
                }
                """);

        KubeJsMixinRuleRegistry.registerForTesting(
                TARGET_CLASS,
                "commitBatch",
                "(Ljava/lang/String;IZ)V",
                KubeJsMixinHook.BEFORE,
                false,
                auditCallback);
        KubeJsMixinRuleRegistry.registerForTesting(
                TARGET_CLASS,
                "commitBatch",
                "(Ljava/lang/String;IZ)V",
                KubeJsMixinHook.BEFORE,
                true,
                cancellationCallback);
        KubeJsMixinRuleRegistry.registerForTesting(
                TARGET_CLASS,
                "processBatch",
                "(Ljava/lang/String;IZ)I",
                KubeJsMixinHook.AFTER,
                false,
                resultCallback);

        Class<?> transformed = defineTransformedTarget();
        Object target = transformed.getDeclaredConstructor().newInstance();
        Method commitBatch = transformed.getMethod("commitBatch", String.class, int.class, boolean.class);
        Method processBatch = transformed.getMethod("processBatch", String.class, int.class, boolean.class);

        commitBatch.invoke(target, "allowed", 3, false);
        commitBatch.invoke(target, "blocked", 100, false);
        assertEquals(9, (Integer) processBatch.invoke(target, "north", 4, false));
        assertEquals(0, (Integer) processBatch.invoke(target, "fast", -2, true));

        assertEquals(7, transformed.getField("hits").getInt(target));
        assertEquals(
                "before:commitBatch:allowed:3:false|before:commitBatch:blocked:100:false|"
                        + "cancelled:blocked|after:processBatch:9:false|after:processBatch:0:true",
                String.valueOf(context.evaluateString(
                        scope,
                        "observed.join('|')",
                        "kubejs-mixin-complex-test.js",
                        1,
                        null)));
    }

    @Test
    void missingExactMethodFailsAtClassTransformationTime() throws IOException {
        KubeJsMixinRuleRegistry.registerForTesting(
                TARGET_CLASS,
                "missing",
                "()V",
                KubeJsMixinHook.BEFORE,
                false,
                invocation -> {
                });

        ClassNode classNode = readTargetClass();
        KubeJsMixinTransformationException exception = assertThrows(
                KubeJsMixinTransformationException.class,
                () -> KubeJsMixinRuleRegistry.transform(TARGET_CLASS, classNode));
        assertTrue(exception.getMessage().contains("missing()V"));
    }

    private static Class<?> defineTransformedTarget() throws IOException {
        ClassNode classNode = readTargetClass();
        KubeJsMixinRuleRegistry.transform(TARGET_CLASS, classNode);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        classNode.accept(writer);
        return defineTransformedTarget(writer.toByteArray());
    }

    private static Class<?> defineTransformedTarget(byte[] bytecode) {
        return new IsolatedClassLoader(KubeJsMixinBytecodeInjectorTest.class.getClassLoader())
                .define(TARGET_CLASS, bytecode);
    }

    private static ClassNode readTargetClass() throws IOException {
        ClassNode classNode = new ClassNode();
        new ClassReader(readTargetClassBytes()).accept(classNode, 0);
        return classNode;
    }

    private static byte[] readTargetClassBytes() throws IOException {
        String resourceName = TARGET_CLASS.replace('.', '/') + ".class";
        try (InputStream input = KubeJsMixinBytecodeInjectorTest.class.getClassLoader().getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IOException("Missing test class resource " + resourceName);
            }
            return input.readAllBytes();
        }
    }

    private static KubeJsMixinCallback createRhinoCallback(Context context, Scriptable scope, String source) {
        Object callback = context.evaluateString(scope, source, "kubejs-mixin-complex-test.js", 1, null);
        context.setTopCall(scope);
        try {
            return (KubeJsMixinCallback) Context.jsToJava(context, callback, KubeJsMixinCallback.class);
        } finally {
            context.setTopCall(null);
        }
    }

    private static void invoke(java.lang.reflect.Method method, Object target) throws Throwable {
        try {
            method.invoke(target);
        } catch (InvocationTargetException exception) {
            throw exception.getCause();
        }
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
