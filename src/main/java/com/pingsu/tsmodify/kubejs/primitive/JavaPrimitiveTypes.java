package com.pingsu.tsmodify.kubejs.primitive;

import dev.latvian.mods.kubejs.script.BindingsEvent;
import dev.latvian.mods.rhino.Wrapper;
import dev.latvian.mods.rhino.util.wrap.TypeWrappers;

import java.math.BigInteger;

/**
 * Global KubeJS bindings for Java primitive values.
 *
 * <p>JavaScript has one floating-point {@code number} type. These factories
 * make the boxed Java value explicit when an overloaded Java API needs a
 * particular primitive, for example {@code LONG.of(1)}.</p>
 */
public final class JavaPrimitiveTypes {
    public static final LongType LONG = new LongType();
    public static final IntegerType INT = new IntegerType();
    public static final ShortType SHORT = new ShortType();
    public static final ByteType BYTE = new ByteType();
    public static final FloatType FLOAT = new FloatType();
    public static final DoubleType DOUBLE = new DoubleType();
    public static final CharacterPrimitiveType CHAR = new CharacterPrimitiveType();
    public static final BooleanPrimitiveType BOOLEAN = new BooleanPrimitiveType();

    private JavaPrimitiveTypes() {
    }

    /** Registers all primitive factories for one KubeJS script scope. */
    public static void registerBindings(BindingsEvent event) {
        event.add("LONG", LONG);
        event.add("INT", INT);
        event.add("SHORT", SHORT);
        event.add("BYTE", BYTE);
        event.add("FLOAT", FLOAT);
        event.add("DOUBLE", DOUBLE);
        event.add("CHAR", CHAR);
        event.add("BOOLEAN", BOOLEAN);
    }

    /**
     * Registers exact primitive conversions for values produced by these factories.
     *
     * <p>Rhino's default numeric overload ranking prefers wider primitives (for example,
     * {@code long} over {@code int}) even when the input is an {@link Integer}. The
     * type-wrapper hook gives a factory value a zero-cost conversion only for its matching
     * primitive target.</p>
     */
    public static void registerTypeWrappers(TypeWrappers wrappers) {
        registerPrimitiveWrapper(wrappers, boolean.class, Boolean.class);
        registerPrimitiveWrapper(wrappers, byte.class, Byte.class);
        registerPrimitiveWrapper(wrappers, short.class, Short.class);
        registerPrimitiveWrapper(wrappers, int.class, Integer.class);
        registerPrimitiveWrapper(wrappers, long.class, Long.class);
        registerPrimitiveWrapper(wrappers, float.class, Float.class);
        registerPrimitiveWrapper(wrappers, double.class, Double.class);
        registerPrimitiveWrapper(wrappers, char.class, Character.class);
    }

    private static <T> void registerPrimitiveWrapper(
            TypeWrappers wrappers,
            Class<T> primitiveClass,
            Class<T> boxedClass) {
        Object sample = defaultValue(boxedClass);
        // KubeJS normally creates one wrapper table per script context, but a
        // reload/test harness can invoke plugin registration more than once.
        // TypeWrappers rejects duplicate targets, so leave an existing entry
        // intact rather than failing the whole script manager initialization.
        if (wrappers.getWrapperFactory(primitiveClass, sample) != null) {
            return;
        }
        try {
            wrappers.registerSimple(
                    primitiveClass,
                    value -> boxedClass.isInstance(unwrap(value)),
                    value -> boxedClass.cast(unwrap(value)));
        } catch (ClassCastException failure) {
            // Rhino 2001.2.x stores the primitive entry before attempting to
            // create an Object[] wrapper for it. Verify that entry before
            // tolerating the known primitive-array cast bug.
            if (wrappers.getWrapperFactory(primitiveClass, sample) == null) {
                throw failure;
            }
        }
    }

    private static Object defaultValue(Class<?> boxedClass) {
        if (boxedClass == Boolean.class) {
            return Boolean.FALSE;
        }
        if (boxedClass == Character.class) {
            return Character.valueOf('\0');
        }
        if (boxedClass == Byte.class) {
            return Byte.valueOf((byte) 0);
        }
        if (boxedClass == Short.class) {
            return Short.valueOf((short) 0);
        }
        if (boxedClass == Integer.class) {
            return Integer.valueOf(0);
        }
        if (boxedClass == Long.class) {
            return Long.valueOf(0L);
        }
        if (boxedClass == Float.class) {
            return Float.valueOf(0F);
        }
        if (boxedClass == Double.class) {
            return Double.valueOf(0D);
        }
        throw new IllegalArgumentException("Unsupported primitive wrapper: " + boxedClass.getName());
    }

    private static Object unwrap(Object value) {
        return value instanceof Wrapper ? Wrapper.unwrapped(value) : value;
    }

    public static final class LongType extends IntegralPrimitiveType<Long> {
        private LongType() {
            super(
                    "long",
                    Long.class,
                    long.class,
                    BigInteger.valueOf(Long.MIN_VALUE),
                    BigInteger.valueOf(Long.MAX_VALUE),
                    BigInteger::longValue);
        }

        @Override
        public long[] array(Object values) {
            return (long[]) super.array(values);
        }

        @Override
        public long[] arrayOf(Object... values) {
            return (long[]) super.arrayOf(values);
        }
    }

    public static final class IntegerType extends IntegralPrimitiveType<Integer> {
        private IntegerType() {
            super("int", Integer.class, int.class, Integer.MIN_VALUE, Integer.MAX_VALUE, BigInteger::intValue);
        }

        @Override
        public int[] array(Object values) {
            return (int[]) super.array(values);
        }

        @Override
        public int[] arrayOf(Object... values) {
            return (int[]) super.arrayOf(values);
        }
    }

    public static final class ShortType extends IntegralPrimitiveType<Short> {
        private ShortType() {
            super("short", Short.class, short.class, Short.MIN_VALUE, Short.MAX_VALUE, BigInteger::shortValue);
        }

        @Override
        public short[] array(Object values) {
            return (short[]) super.array(values);
        }

        @Override
        public short[] arrayOf(Object... values) {
            return (short[]) super.arrayOf(values);
        }
    }

    public static final class ByteType extends IntegralPrimitiveType<Byte> {
        private ByteType() {
            super("byte", Byte.class, byte.class, Byte.MIN_VALUE, Byte.MAX_VALUE, BigInteger::byteValue);
        }

        @Override
        public byte[] array(Object values) {
            return (byte[]) super.array(values);
        }

        @Override
        public byte[] arrayOf(Object... values) {
            return (byte[]) super.arrayOf(values);
        }
    }

    public static final class FloatType extends FloatingPrimitiveType<Float> {
        private FloatType() {
            super("float", Float.class, float.class, Float.MIN_VALUE, Float.MAX_VALUE, value -> (float) value);
        }

        @Override
        public float[] array(Object values) {
            return (float[]) super.array(values);
        }

        @Override
        public float[] arrayOf(Object... values) {
            return (float[]) super.arrayOf(values);
        }
    }

    public static final class DoubleType extends FloatingPrimitiveType<Double> {
        private DoubleType() {
            super("double", Double.class, double.class, Double.MIN_VALUE, Double.MAX_VALUE, value -> value);
        }

        @Override
        public double[] array(Object values) {
            return (double[]) super.array(values);
        }

        @Override
        public double[] arrayOf(Object... values) {
            return (double[]) super.arrayOf(values);
        }
    }
}
