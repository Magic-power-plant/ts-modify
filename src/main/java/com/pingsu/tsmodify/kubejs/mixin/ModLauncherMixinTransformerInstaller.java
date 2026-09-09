package com.pingsu.tsmodify.kubejs.mixin;

import cpw.mods.modlauncher.Launcher;
import cpw.mods.modlauncher.TransformList;
import cpw.mods.modlauncher.TransformStore;
import cpw.mods.modlauncher.TransformTargetLabel;
import cpw.mods.modlauncher.TransformerHolder;
import cpw.mods.modlauncher.TransformingClassLoader;
import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformer.Target;
import cpw.mods.modlauncher.api.ITransformer.TargetType;
import org.objectweb.asm.tree.ClassNode;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Installs script-selected class transformers into the pinned ModLauncher transform store.
 *
 * <p>ModLauncher builds its transform store before KubeJS startup scripts run, but does not
 * expose a public late-registration API. Forge 47.4.10 ships ModLauncher 10.0.9, whose store
 * layout is checked here before {@link Unsafe} reads the required private fields. The adapter
 * deliberately fails closed for every other layout or version.</p>
 */
final class ModLauncherMixinTransformerInstaller {
    private static final String EXPECTED_MODLAUNCHER_VERSION = "10.0.9";
    private static final Object LOCK = new Object();
    private static final Set<String> INSTALLED_TARGETS = new HashSet<>();
    private static final ITransformationService OWNER = new KubeJsMixinTransformationService();
    private static final Unsafe UNSAFE = findUnsafe();
    private static final long LAUNCHER_TRANSFORM_STORE_OFFSET = objectFieldOffset(
            Launcher.class, "transformStore", TransformStore.class);
    private static final long LAUNCHER_CLASS_LOADER_OFFSET = objectFieldOffset(
            Launcher.class, "classLoader", TransformingClassLoader.class);
    private static final long TRANSFORM_STORE_CLASS_NEEDS_TRANSFORMING_OFFSET = objectFieldOffset(
            TransformStore.class, "classNeedsTransforming", Set.class);
    private static final long TRANSFORM_STORE_TRANSFORMERS_OFFSET = objectFieldOffset(
            TransformStore.class, "transformers", EnumMap.class);
    private static final long TRANSFORM_LIST_TRANSFORMERS_OFFSET = objectFieldOffset(
            TransformList.class, "transformers", Map.class);

    private ModLauncherMixinTransformerInstaller() {
    }

    static void install(String targetClass) {
        Objects.requireNonNull(targetClass, "KubeJS mixin target class cannot be null");
        KubeJsMixinTargetPolicy.validateTargetClass(targetClass);
        synchronized (LOCK) {
            if (INSTALLED_TARGETS.contains(targetClass)) {
                Launcher launcher = Launcher.INSTANCE;
                if (launcher != null) {
                    TransformingClassLoader classLoader = readObject(
                            launcher,
                            LAUNCHER_CLASS_LOADER_OFFSET,
                            TransformingClassLoader.class,
                            "Launcher#classLoader");
                    requireClassNotLoaded(classLoader, targetClass);
                }
                return;
            }

            Launcher launcher = Launcher.INSTANCE;
            if (launcher == null) {
                throw unsupported("ModLauncher has not created its launcher instance");
            }
            requireSupportedModLauncher(launcher);

            TransformStore transformStore = readObject(
                    launcher,
                    LAUNCHER_TRANSFORM_STORE_OFFSET,
                    TransformStore.class,
                    "Launcher#transformStore");
            TransformingClassLoader classLoader = readObject(
                    launcher,
                    LAUNCHER_CLASS_LOADER_OFFSET,
                    TransformingClassLoader.class,
                    "Launcher#classLoader");
            requireClassNotLoaded(classLoader, targetClass);
            installIntoStore(transformStore, targetClass);
            requireClassNotLoaded(classLoader, targetClass);
            INSTALLED_TARGETS.add(targetClass);
        }
    }

    /**
     * Shared with package-local tests to verify the pinned transform-store layout without
     * requiring a live ModLauncher instance.
     */
    @SuppressWarnings("deprecation")
    static void installIntoStore(TransformStore transformStore, String targetClass) {
        Objects.requireNonNull(transformStore, "ModLauncher transform store cannot be null");
        Objects.requireNonNull(targetClass, "KubeJS mixin target class cannot be null");
        KubeJsMixinTargetPolicy.validateTargetClass(targetClass);
        Set<?> rawClassNeedsTransforming = readObject(
                transformStore,
                TRANSFORM_STORE_CLASS_NEEDS_TRANSFORMING_OFFSET,
                Set.class,
                "TransformStore#classNeedsTransforming");
        EnumMap<?, ?> transformLists = readObject(
                transformStore,
                TRANSFORM_STORE_TRANSFORMERS_OFFSET,
                EnumMap.class,
                "TransformStore#transformers");
        Object rawClassTransformList = transformLists.get(TransformTargetLabel.LabelType.CLASS);
        if (!(rawClassTransformList instanceof TransformList<?> classTransformList)) {
            throw unsupported("TransformStore does not contain a CLASS TransformList");
        }

        Map<?, ?> rawTransformers = readObject(
                classTransformList,
                TRANSFORM_LIST_TRANSFORMERS_OFFSET,
                Map.class,
                "TransformList#transformers");
        if (!(rawTransformers instanceof ConcurrentMap<?, ?>)) {
            throw unsupported("TransformList transformer map is not concurrent");
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        ConcurrentMap<TransformTargetLabel, List<ITransformer<ClassNode>>> transformers =
                (ConcurrentMap) rawTransformers;
        TransformTargetLabel targetLabel = new TransformTargetLabel(targetClass);
        TransformerHolder<ClassNode> transformer = new TransformerHolder<>(
                new KubeJsMixinClassTransformer(targetClass),
                OWNER);

        // Copy-on-write keeps a concurrently iterating ModLauncher transform pass stable.
        boolean[] inserted = new boolean[1];
        transformers.compute(targetLabel, (ignored, existing) -> {
            List<ITransformer<ClassNode>> updated = new CopyOnWriteArrayList<>();
            if (existing != null) {
                updated.addAll(existing);
            }
            if (!containsOwnedTransformer(updated, targetClass)) {
                updated.add(transformer);
                inserted[0] = true;
            }
            return updated;
        });

        try {
            // Mark the class only after its transformer is visible to ModLauncher.
            makeClassNeedsTransformingThreadSafe(transformStore, rawClassNeedsTransforming, targetClass);
        } catch (RuntimeException | LinkageError failure) {
            if (inserted[0]) {
                removeTransformer(transformers, targetLabel, transformer);
            }
            throw failure;
        }
    }

    private static boolean containsOwnedTransformer(
            List<ITransformer<ClassNode>> transformers,
            String targetClass) {
        for (ITransformer<ClassNode> candidate : transformers) {
            if (!(candidate instanceof TransformerHolder<?> holder) || holder.owner() != OWNER) {
                continue;
            }
            for (Target target : holder.targets()) {
                if (target.getTargetType() == TargetType.CLASS
                        && targetClass.equals(target.getClassName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void removeTransformer(
            ConcurrentMap<TransformTargetLabel, List<ITransformer<ClassNode>>> transformers,
            TransformTargetLabel targetLabel,
            ITransformer<ClassNode> transformer) {
        transformers.computeIfPresent(targetLabel, (ignored, existing) -> {
            List<ITransformer<ClassNode>> updated = new CopyOnWriteArrayList<>(existing);
            updated.removeIf(candidate -> candidate == transformer);
            return updated.isEmpty() ? null : updated;
        });
    }

    private static void makeClassNeedsTransformingThreadSafe(
            TransformStore transformStore,
            Set<?> current,
            String targetClass) {
        for (Object value : current) {
            if (!(value instanceof String)) {
                throw unsupported("TransformStore classNeedsTransforming contains a non-string value");
            }
        }
        if (current instanceof ConcurrentHashMap.KeySetView<?, ?> keySet) {
            @SuppressWarnings("unchecked")
            Set<String> classNeedsTransforming = (Set<String>) keySet;
            classNeedsTransforming.add(targetClass.replace('.', '/'));
            return;
        }

        Set<String> replacement = ConcurrentHashMap.newKeySet();
        for (Object value : current) {
            replacement.add((String) value);
        }
        replacement.add(targetClass.replace('.', '/'));
        UNSAFE.putObjectVolatile(transformStore, TRANSFORM_STORE_CLASS_NEEDS_TRANSFORMING_OFFSET, replacement);
        UNSAFE.fullFence();
    }

    private static void requireSupportedModLauncher(Launcher launcher) {
        String implementationVersion = launcher.environment()
                .getProperty(IEnvironment.Keys.MLIMPL_VERSION.get())
                .orElseGet(() -> Launcher.class.getPackage().getImplementationVersion());
        if (!isExpectedVersion(implementationVersion)) {
            throw unsupported(
                    "expected ModLauncher " + EXPECTED_MODLAUNCHER_VERSION + "[+build], found "
                            + (implementationVersion == null ? "an unknown version" : implementationVersion));
        }
    }

    private static boolean isExpectedVersion(String implementationVersion) {
        return implementationVersion != null
                && (implementationVersion.equals(EXPECTED_MODLAUNCHER_VERSION)
                || implementationVersion.startsWith(EXPECTED_MODLAUNCHER_VERSION + "+"));
    }

    private static void requireClassNotLoaded(TransformingClassLoader classLoader, String targetClass) {
        if (classLoader.getLoadedClass(targetClass) != null) {
            throw new IllegalStateException(
                    "KubeJS mixin target is already defined and cannot be transformed safely: " + targetClass);
        }
    }

    private static Unsafe findUnsafe() {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            if (!field.trySetAccessible()) {
                throw unsupported("the JDK does not expose sun.misc.Unsafe");
            }
            return (Unsafe) field.get(null);
        } catch (ReflectiveOperationException | SecurityException exception) {
            throw unsupported("sun.misc.Unsafe is unavailable", exception);
        }
    }

    private static long objectFieldOffset(Class<?> owner, String fieldName, Class<?> expectedType) {
        try {
            Field field = owner.getDeclaredField(fieldName);
            if (Modifier.isStatic(field.getModifiers()) || field.getType() != expectedType) {
                throw unsupported(
                        "unexpected field layout for " + owner.getName() + "#" + fieldName);
            }
            return UNSAFE.objectFieldOffset(field);
        } catch (NoSuchFieldException exception) {
            throw unsupported("missing required field " + owner.getName() + "#" + fieldName, exception);
        }
    }

    private static <T> T readObject(Object owner, long offset, Class<T> expectedType, String fieldDescription) {
        Object value = UNSAFE.getObject(owner, offset);
        if (!expectedType.isInstance(value)) {
            throw unsupported("unexpected value for " + fieldDescription);
        }
        return expectedType.cast(value);
    }

    private static IllegalStateException unsupported(String detail) {
        return new IllegalStateException(
                "KubeJS mixin hooks require the pinned Forge 47.4.10 / ModLauncher 10.0.9 layout: " + detail);
    }

    private static IllegalStateException unsupported(String detail, Throwable cause) {
        return new IllegalStateException(
                "KubeJS mixin hooks require the pinned Forge 47.4.10 / ModLauncher 10.0.9 layout: " + detail,
                cause);
    }

    private static final class KubeJsMixinTransformationService implements ITransformationService {
        @Override
        public String name() {
            return "tsmodify_kubejs";
        }

        @Override
        public void initialize(IEnvironment environment) {
        }

        @Override
        public void onLoad(IEnvironment environment, Set<String> otherServices) {
        }

        @Override
        @SuppressWarnings("rawtypes")
        public List<ITransformer> transformers() {
            return List.of();
        }
    }
}
