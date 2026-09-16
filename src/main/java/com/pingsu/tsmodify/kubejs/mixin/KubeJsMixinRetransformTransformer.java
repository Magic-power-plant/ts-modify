package com.pingsu.tsmodify.kubejs.mixin;

import dev.latvian.mods.kubejs.KubeJS;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The retransformation half of the two mutually exclusive KubeJS mixin transformation paths.
 *
 * <p>It is registered with the optional TS Modify Java agent (see {@link KubeJsMixinRetransform})
 * and deliberately ignores every load-time callback: classes that are not defined yet keep using
 * the ModLauncher transform store path. Only a retransformation callback ({@code
 * classBeingRedefined != null}) for a tracked target is rewritten.</p>
 *
 * <p>Every non-target class costs one {@link Set} lookup and returns {@code null} immediately. Any
 * injection failure is fail-closed: {@code null} is returned so the JVM keeps the previous
 * bytecode, and the failure is logged with the target class name.</p>
 */
final class KubeJsMixinRetransformTransformer implements ClassFileTransformer {
    private final Set<String> trackedInternalNames = ConcurrentHashMap.newKeySet();

    /**
     * Starts rewriting retransformation callbacks for one binary class name.
     */
    void track(String binaryName) {
        Objects.requireNonNull(binaryName, "KubeJS mixin retransformation target cannot be null");
        trackedInternalNames.add(binaryName.replace('.', '/'));
    }

    @Override
    public byte[] transform(
            ClassLoader loader,
            String className,
            Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain,
            byte[] classfileBuffer) {
        if (classBeingRedefined == null || className == null || classfileBuffer == null
                || !trackedInternalNames.contains(className)) {
            return null;
        }
        try {
            ClassReader reader = new ClassReader(classfileBuffer);
            ClassNode targetNode = new ClassNode();
            reader.accept(targetNode, 0);
            int injectedRules = KubeJsMixinRuleRegistry.transform(className.replace('/', '.'), targetNode);
            if (injectedRules == 0) {
                return null;
            }
            ClassWriter writer = new FrameComputingClassWriter(reader, classBeingRedefined);
            targetNode.accept(writer);
            KubeJS.LOGGER.info(
                    "KubeJS mixin retransformation injected {} rule(s) into {}",
                    injectedRules,
                    className.replace('/', '.'));
            return writer.toByteArray();
        } catch (Throwable failure) {
            KubeJS.LOGGER.error(
                    "KubeJS mixin retransformation failed for {}; the class keeps its previous bytecode",
                    className,
                    failure);
            return null;
        }
    }
}
