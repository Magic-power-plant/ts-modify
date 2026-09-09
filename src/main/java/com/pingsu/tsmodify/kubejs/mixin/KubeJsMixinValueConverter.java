package com.pingsu.tsmodify.kubejs.mixin;

import dev.latvian.mods.rhino.Wrapper;
import org.objectweb.asm.Type;

/**
 * Converts values written by JavaScript into the boxed form expected by a JVM slot.
 */
final class KubeJsMixinValueConverter {
    private KubeJsMixinValueConverter() {
    }

    static Object coerce(Object value, Type type) {
        Object unwrapped = unwrap(value);
        return switch (type.getSort()) {
            case Type.BOOLEAN -> booleanValue(unwrapped);
            case Type.BYTE -> (byte) integralValue(unwrapped, Byte.MIN_VALUE, Byte.MAX_VALUE, "byte");
            case Type.CHAR -> characterValue(unwrapped);
            case Type.SHORT -> (short) integralValue(unwrapped, Short.MIN_VALUE, Short.MAX_VALUE, "short");
            case Type.INT -> (int) integralValue(unwrapped, Integer.MIN_VALUE, Integer.MAX_VALUE, "int");
            case Type.FLOAT -> floatingValue(unwrapped, "float").floatValue();
            case Type.LONG -> longValue(unwrapped);
            case Type.DOUBLE -> floatingValue(unwrapped, "double").doubleValue();
            case Type.OBJECT, Type.ARRAY -> unwrapped;
            default -> throw new IllegalArgumentException("KubeJS mixin cannot write a value to " + type.getDescriptor());
        };
    }

    private static Object unwrap(Object value) {
        return value instanceof Wrapper ? Wrapper.unwrapped(value) : value;
    }

    private static Boolean booleanValue(Object value) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        throw incompatible(value, "boolean");
    }

    private static Character characterValue(Object value) {
        if (value instanceof Character characterValue) {
            return characterValue;
        }
        if (value instanceof CharSequence text && text.length() == 1) {
            return text.charAt(0);
        }
        throw incompatible(value, "char");
    }

    private static Number floatingValue(Object value, String targetType) {
        if (value instanceof Number number) {
            return number;
        }
        throw incompatible(value, targetType);
    }

    private static long integralValue(Object value, long minimum, long maximum, String targetType) {
        if (!(value instanceof Number number)) {
            throw incompatible(value, targetType);
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            long longValue = number.longValue();
            if (longValue >= minimum && longValue <= maximum) {
                return longValue;
            }
            throw incompatible(value, targetType);
        }
        double doubleValue = number.doubleValue();
        if (!Double.isFinite(doubleValue)
                || Math.rint(doubleValue) != doubleValue
                || doubleValue < minimum
                || doubleValue > maximum) {
            throw incompatible(value, targetType);
        }
        return (long) doubleValue;
    }

    private static Long longValue(Object value) {
        return integralValue(value, Long.MIN_VALUE, Long.MAX_VALUE, "long");
    }

    private static IllegalArgumentException incompatible(Object value, String targetType) {
        String sourceType = value == null ? "null" : value.getClass().getName();
        return new IllegalArgumentException(
                "KubeJS mixin value of type " + sourceType + " cannot be converted to " + targetType);
    }
}
