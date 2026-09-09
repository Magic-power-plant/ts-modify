package com.pingsu.tsmodify.kubejs.mixin;

import org.objectweb.asm.Type;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

final class KubeJsMixinTargetPolicy {
    private static final int MAX_CLASS_NAME_LENGTH = 512;
    private static final int MAX_METHOD_NAME_LENGTH = 256;
    private static final int MAX_DESCRIPTOR_LENGTH = 4096;
    private static final int MAX_LOCAL_INDEX = 65535;
    private static final Pattern BINARY_CLASS_NAME = Pattern.compile(
            "[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+");
    private static final Pattern INTERNAL_CLASS_NAME = Pattern.compile(
            "[A-Za-z_$][A-Za-z0-9_$]*(?:/[A-Za-z_$][A-Za-z0-9_$]*)+");
    private static final Pattern METHOD_NAME = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");
    private static final List<String> PROTECTED_PREFIXES = List.of(
            "java.",
            "javax.",
            "jdk.",
            "sun.",
            "com.sun.",
            "cpw.mods.",
            "net.minecraftforge.",
            "org.objectweb.asm.",
            "org.spongepowered.",
            "dev.latvian.mods.rhino.",
            "dev.latvian.mods.kubejs.",
            "com.pingsu.tsmodify.");
    private static final Set<String> PROTECTED_CLASSES = Set.of(
            "net.minecraft.SharedConstants",
            "net.minecraft.client.Minecraft",
            "net.minecraft.client.main.Main",
            "net.minecraft.server.Bootstrap",
            "net.minecraft.server.Main",
            "net.minecraft.server.MinecraftServer",
            "net.minecraft.server.dedicated.DedicatedServer");

    private KubeJsMixinTargetPolicy() {
    }

    static void validateTargetClass(String targetClass) {
        if (targetClass == null) {
            throw new IllegalArgumentException("KubeJS mixin target class cannot be null");
        }
        if (targetClass.length() > MAX_CLASS_NAME_LENGTH) {
            throw new IllegalArgumentException("KubeJS mixin target class name is too long");
        }
        if (!BINARY_CLASS_NAME.matcher(targetClass).matches()) {
            throw new IllegalArgumentException("KubeJS mixin targets must use a binary class name, such as com.example.Target");
        }
        if (isProtected(targetClass)) {
            throw new IllegalArgumentException("KubeJS mixin target is protected for runtime safety: " + targetClass);
        }
    }

    static void validate(
            String targetClass,
            String methodName,
            String methodDescriptor,
            KubeJsMixinHook hook,
            boolean cancellable,
            KubeJsMixinInstructionTarget instructionTarget) {
        validateTargetClass(targetClass);
        if (hook == null) {
            throw new IllegalArgumentException("KubeJS mixin hook cannot be null");
        }
        if (methodName == null) {
            throw new IllegalArgumentException("KubeJS mixin method name cannot be null");
        }
        if (methodName.length() > MAX_METHOD_NAME_LENGTH) {
            throw new IllegalArgumentException("KubeJS mixin method name is too long");
        }
        if (!METHOD_NAME.matcher(methodName).matches()) {
            throw new IllegalArgumentException("KubeJS mixin hooks do not support constructors, class initializers, or invalid method names");
        }
        if (methodDescriptor == null) {
            throw new IllegalArgumentException("KubeJS mixin method descriptor cannot be null");
        }
        if (methodDescriptor.length() > MAX_DESCRIPTOR_LENGTH) {
            throw new IllegalArgumentException("KubeJS mixin method descriptor is too long");
        }

        Type methodType = parseMethodDescriptor(methodDescriptor, "KubeJS mixin method descriptor");
        if (cancellable && hook != KubeJsMixinHook.BEFORE) {
            throw new IllegalArgumentException("KubeJS cancellable hooks must use the before hook");
        }
        if (hook == KubeJsMixinHook.MODIFY_RETURN_VALUE
                && methodType.getReturnType().getSort() == Type.VOID) {
            throw new IllegalArgumentException("KubeJS modifyReturnValue requires a non-void target method");
        }
        if (hook.requiresInstructionTarget() != (instructionTarget != null)) {
            throw new IllegalArgumentException(
                    "KubeJS " + hook.displayName()
                            + (hook.requiresInstructionTarget()
                            ? " requires an exact instruction target"
                            : " cannot use an instruction target"));
        }
        if (instructionTarget != null) {
            validateInstructionTarget(hook, instructionTarget);
        }
    }

    private static void validateInstructionTarget(
            KubeJsMixinHook hook,
            KubeJsMixinInstructionTarget instructionTarget) {
        if (instructionTarget instanceof KubeJsMixinInstructionTarget.Invoke invoke) {
            if (hook != KubeJsMixinHook.REDIRECT && hook != KubeJsMixinHook.MODIFY_ARG) {
                throw new IllegalArgumentException(
                        "KubeJS " + hook.displayName() + " cannot target a method invocation");
            }
            if (invoke.owner() == null || invoke.owner().length() > MAX_CLASS_NAME_LENGTH
                    || !INTERNAL_CLASS_NAME.matcher(invoke.owner()).matches()) {
                throw new IllegalArgumentException("KubeJS instruction owner must be a binary class name");
            }
            validateInstructionMethodName(invoke.methodName());
            Type invokeType = parseMethodDescriptor(
                    invoke.methodDescriptor(),
                    "KubeJS invocation descriptor");
            if (invoke.ordinal() < 0) {
                throw new IllegalArgumentException("KubeJS invocation ordinal must be zero or greater");
            }
            if (hook == KubeJsMixinHook.MODIFY_ARG) {
                if (invoke.argumentIndex() < 0 || invoke.argumentIndex() >= invokeType.getArgumentTypes().length) {
                    throw new IllegalArgumentException(
                            "KubeJS modifyArg index " + invoke.argumentIndex()
                                    + " is outside the invocation argument range");
                }
            } else if (invoke.argumentIndex() != -1) {
                throw new IllegalArgumentException("KubeJS redirect cannot select one invocation argument");
            }
            return;
        }
        if (instructionTarget instanceof KubeJsMixinInstructionTarget.Variable variable) {
            if (hook != KubeJsMixinHook.MODIFY_VARIABLE) {
                throw new IllegalArgumentException(
                        "KubeJS " + hook.displayName() + " cannot target a local variable");
            }
            if (variable.localIndex() < 0 || variable.localIndex() > MAX_LOCAL_INDEX) {
                throw new IllegalArgumentException("KubeJS local variable index is outside the JVM range");
            }
            if (variable.access() == null) {
                throw new IllegalArgumentException("KubeJS modifyVariable access cannot be null");
            }
            if (variable.ordinal() < 0) {
                throw new IllegalArgumentException("KubeJS modifyVariable ordinal must be zero or greater");
            }
            Type variableType = parseValueDescriptor(variable.descriptor(), "KubeJS local variable descriptor");
            if (variableType.getSort() == Type.VOID) {
                throw new IllegalArgumentException("KubeJS modifyVariable cannot target void");
            }
            return;
        }
        throw new IllegalArgumentException("KubeJS mixin instruction target has an unsupported type");
    }

    private static void validateInstructionMethodName(String methodName) {
        if (methodName == null || methodName.length() > MAX_METHOD_NAME_LENGTH
                || !METHOD_NAME.matcher(methodName).matches()) {
            throw new IllegalArgumentException(
                    "KubeJS instruction targets must name a non-constructor method");
        }
    }

    private static Type parseMethodDescriptor(String descriptor, String description) {
        if (descriptor == null || descriptor.length() > MAX_DESCRIPTOR_LENGTH) {
            throw new IllegalArgumentException(description + " is missing or too long");
        }
        try {
            return Type.getMethodType(descriptor);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(description + " is invalid: " + descriptor, exception);
        }
    }

    private static Type parseValueDescriptor(String descriptor, String description) {
        if (descriptor == null || descriptor.length() > MAX_DESCRIPTOR_LENGTH) {
            throw new IllegalArgumentException(description + " is missing or too long");
        }
        try {
            Type type = Type.getType(descriptor);
            if (type.getSort() == Type.METHOD) {
                throw new IllegalArgumentException(description + " must describe one value");
            }
            return type;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(description + " is invalid: " + descriptor, exception);
        }
    }

    static boolean isProtected(String targetClass) {
        if (targetClass == null) {
            return false;
        }
        if (PROTECTED_CLASSES.stream().anyMatch(
                protectedClass -> targetClass.equals(protectedClass)
                        || targetClass.startsWith(protectedClass + "$"))) {
            return true;
        }
        return PROTECTED_PREFIXES.stream().anyMatch(targetClass::startsWith);
    }
}
