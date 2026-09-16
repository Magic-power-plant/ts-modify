package com.pingsu.tsmodify.kubejs.mixin;

import dev.latvian.mods.kubejs.KubeJS;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

/**
 * A {@link ClassWriter} that recomputes stack map frames for a retransformed class.
 *
 * <p>The bytecode handed to a retransformation transformer is the class definition the JVM
 * currently holds, and the injector inserts branches, so a valid {@code StackMapTable} must be
 * regenerated. The default {@link ClassWriter#getCommonSuperClass(String, String)} resolves types
 * with the loader of ASM itself, which cannot see game classes; this writer resolves them with the
 * class loader of the class being retransformed instead.</p>
 *
 * <p>Resolution failures degrade to {@code java/lang/Object}, which is always a legal conservative
 * answer, and are reported once per occurrence at DEBUG level.</p>
 */
final class FrameComputingClassWriter extends ClassWriter {
    private static final String OBJECT = "java/lang/Object";

    private final ClassLoader targetClassLoader;
    private final String targetClassName;

    FrameComputingClassWriter(ClassReader reader, Class<?> targetClass) {
        super(reader, ClassWriter.COMPUTE_FRAMES);
        this.targetClassLoader = targetClass.getClassLoader();
        this.targetClassName = targetClass.getName();
    }

    @Override
    protected String getCommonSuperClass(String type1, String type2) {
        try {
            Class<?> left = resolve(type1);
            Class<?> right = resolve(type2);
            if (left.isAssignableFrom(right)) {
                return type1;
            }
            if (right.isAssignableFrom(left)) {
                return type2;
            }
            if (left.isInterface() || right.isInterface()) {
                return OBJECT;
            }
            Class<?> candidate = left;
            while (candidate != null && !candidate.isAssignableFrom(right)) {
                candidate = candidate.getSuperclass();
            }
            return candidate == null ? OBJECT : candidate.getName().replace('.', '/');
        } catch (ClassNotFoundException | LinkageError | SecurityException failure) {
            KubeJS.LOGGER.debug(
                    "KubeJS mixin retransformation could not resolve the common superclass of {} and {} for {}; "
                            + "falling back to {}",
                    type1,
                    type2,
                    targetClassName,
                    OBJECT,
                    failure);
            return OBJECT;
        }
    }

    private Class<?> resolve(String internalName) throws ClassNotFoundException {
        return Class.forName(internalName.replace('/', '.'), false, targetClassLoader);
    }
}
