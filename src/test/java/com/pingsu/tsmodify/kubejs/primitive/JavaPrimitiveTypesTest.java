package com.pingsu.tsmodify.kubejs.primitive;

import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.Scriptable;
import dev.latvian.mods.rhino.Wrapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaPrimitiveTypesTest {
    @Test
    void convertsJavaScriptStyleValuesToExactBoxedTypes() {
        assertEquals(Long.valueOf(42L), JavaPrimitiveTypes.LONG.of(42));
        assertEquals(Long.valueOf(Long.MAX_VALUE), JavaPrimitiveTypes.LONG.of("9223372036854775807"));
        assertEquals(Integer.valueOf(-12), JavaPrimitiveTypes.INT.of(-12));
        assertEquals(Short.valueOf((short) 12), JavaPrimitiveTypes.SHORT.of("12"));
        assertEquals(Byte.valueOf((byte) 12), JavaPrimitiveTypes.BYTE.of(12));
        assertEquals(Float.valueOf(1.25F), JavaPrimitiveTypes.FLOAT.of(1.25));
        assertEquals(Double.valueOf(1.25D), JavaPrimitiveTypes.DOUBLE.of("1.25"));
        assertEquals(Character.valueOf('A'), JavaPrimitiveTypes.CHAR.of("A"));
        assertEquals(Character.valueOf('A'), JavaPrimitiveTypes.CHAR.of(65));
        assertEquals(Boolean.TRUE, JavaPrimitiveTypes.BOOLEAN.of(1));
        assertEquals(Boolean.FALSE, JavaPrimitiveTypes.BOOLEAN.of("0"));

        assertTrue(JavaPrimitiveTypes.LONG.is(JavaPrimitiveTypes.LONG.of(1)));
        assertTrue(!JavaPrimitiveTypes.LONG.is(1));
        assertEquals(long.class, JavaPrimitiveTypes.LONG.primitiveClass());
        assertEquals(Long.class, JavaPrimitiveTypes.LONG.boxedClass());
    }

    @Test
    void rejectsLossyOrOutOfRangeConversions() {
        assertThrows(IllegalArgumentException.class, () -> JavaPrimitiveTypes.LONG.of(1.5));
        assertThrows(IllegalArgumentException.class, () -> JavaPrimitiveTypes.INT.of(Integer.MAX_VALUE + 1L));
        assertThrows(IllegalArgumentException.class, () -> JavaPrimitiveTypes.BYTE.of(128));
        assertThrows(IllegalArgumentException.class, () -> JavaPrimitiveTypes.LONG.of(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> JavaPrimitiveTypes.CHAR.of("too long"));
        assertThrows(IllegalArgumentException.class, () -> JavaPrimitiveTypes.BOOLEAN.of(2));
        assertThrows(IllegalArgumentException.class, () -> JavaPrimitiveTypes.BOOLEAN.of(new BigDecimal("1.0000000000000001")));
        assertThrows(IllegalArgumentException.class, () -> JavaPrimitiveTypes.LONG.of(null));
    }

    @Test
    void createsPrimitiveArraysFromCollectionsAndVarargs() {
        assertArrayEquals(
                new long[]{1L, 2L, 3L},
                (long[]) JavaPrimitiveTypes.LONG.array(Arrays.asList(1, 2, 3)));
        assertArrayEquals(
                new int[]{1, 2, 3},
                (int[]) JavaPrimitiveTypes.INT.arrayOf(1, 2, 3));
        assertArrayEquals(
                new char[]{'a', 'b'},
                (char[]) JavaPrimitiveTypes.CHAR.array("ab"));
        assertArrayEquals(
                new boolean[]{true, false},
                (boolean[]) JavaPrimitiveTypes.BOOLEAN.arrayOf(true, false));
        assertInstanceOf(long[].class, JavaPrimitiveTypes.LONG.array(new BigInteger[]{BigInteger.ONE}));
    }

    @Test
    void exposesFactoriesToRhinoAndUnboxesLongAtJavaBoundaries() {
        Context context = Context.enter();
        Scriptable scope = context.initStandardObjects();
        context.addToScope(scope, "LONG", JavaPrimitiveTypes.LONG);
        context.addToScope(scope, "INT", JavaPrimitiveTypes.INT);
        context.addToScope(scope, "SHORT", JavaPrimitiveTypes.SHORT);
        context.addToScope(scope, "BYTE", JavaPrimitiveTypes.BYTE);
        context.addToScope(scope, "FLOAT", JavaPrimitiveTypes.FLOAT);
        context.addToScope(scope, "DOUBLE", JavaPrimitiveTypes.DOUBLE);
        context.addToScope(scope, "CHAR", JavaPrimitiveTypes.CHAR);
        context.addToScope(scope, "BOOLEAN", JavaPrimitiveTypes.BOOLEAN);
        context.addToScope(scope, "Target", PrimitiveTarget.class);
        JavaPrimitiveTypes.registerTypeWrappers(context.getTypeWrappers());
        JavaPrimitiveTypes.registerTypeWrappers(context.getTypeWrappers());

        Object longValue = context.evaluateString(
                scope,
                "LONG.of('9223372036854775807');",
                "primitive-types.ts",
                1,
                null);
        assertInstanceOf(Long.class, ((Wrapper) longValue).unwrap());
        assertEquals(
                "9223372036854775807",
                unwrap(context.evaluateString(
                        scope,
                        "Target.acceptLong(LONG.of('9223372036854775807'));",
                        "primitive-types.ts",
                        1,
                        null)));
        assertEquals(
                6D,
                unwrap(context.evaluateString(scope, "INT.of(5) + 1;", "primitive-types.ts", 1, null)));
        assertEquals(
                16L,
                ((Number) unwrap(context.evaluateString(
                        scope,
                        "LONG.parse('0x10');",
                        "primitive-types.ts",
                        1,
                        null))).longValue());
        assertEquals(
                "A",
                String.valueOf(unwrap(context.evaluateString(
                        scope,
                        "CHAR.valueOf('A');",
                        "primitive-types.ts",
                        1,
                        null))));
        assertEquals(
                "int",
                unwrap(context.evaluateString(
                        scope,
                        "Target.overloaded(INT.of(7));",
                        "primitive-types.ts",
                        1,
                        null)));
        assertEquals(
                "long",
                unwrap(context.evaluateString(scope, "Target.overloaded(LONG.of(7));", "primitive-types.ts", 1, null)));
        assertEquals(
                "short",
                unwrap(context.evaluateString(scope, "Target.overloaded(SHORT.of(7));", "primitive-types.ts", 1, null)));
        assertEquals(
                "byte",
                unwrap(context.evaluateString(scope, "Target.overloaded(BYTE.of(7));", "primitive-types.ts", 1, null)));
        assertEquals(
                "float",
                unwrap(context.evaluateString(scope, "Target.overloaded(FLOAT.of(7));", "primitive-types.ts", 1, null)));
        assertEquals(
                "double",
                unwrap(context.evaluateString(scope, "Target.overloaded(DOUBLE.of(7));", "primitive-types.ts", 1, null)));
        assertEquals(
                "char",
                unwrap(context.evaluateString(scope, "Target.overloaded(CHAR.of('A'));", "primitive-types.ts", 1, null)));
        assertEquals(
                "boolean",
                unwrap(context.evaluateString(scope, "Target.overloaded(BOOLEAN.of(true));", "primitive-types.ts", 1, null)));
        assertEquals(
                "long:3",
                unwrap(context.evaluateString(
                        scope,
                        "Target.acceptLongArray(LONG.array([1, 2, 3]));",
                        "primitive-types.ts",
                        1,
                        null)));
        assertEquals(
                "boolean:2",
                unwrap(context.evaluateString(
                        scope,
                        "Target.acceptBooleanArray(BOOLEAN.arrayOf([true, false]));",
                        "primitive-types.ts",
                        1,
                        null)));
        Object booleanArray = context.evaluateString(
                scope,
                "BOOLEAN.arrayOf([true, false]);",
                "primitive-types.ts",
                1,
                null);
        assertInstanceOf(boolean[].class, ((Wrapper) booleanArray).unwrap());
        Object array = context.evaluateString(
                scope,
                "LONG.array([1, 2, 3]);",
                "primitive-types.ts",
                1,
                null);
        assertInstanceOf(long[].class, ((Wrapper) array).unwrap());
        Object charArray = context.evaluateString(
                scope,
                "CHAR.arrayOf('xy');",
                "primitive-types.ts",
                1,
                null);
        assertInstanceOf(char[].class, ((Wrapper) charArray).unwrap());
    }

    public static final class PrimitiveTarget {
        private PrimitiveTarget() {
        }

        public static String acceptLong(long value) {
            return Long.toString(value);
        }

        public static String overloaded(int value) {
            return "int";
        }

        public static String overloaded(long value) {
            return "long";
        }

        public static String overloaded(short value) {
            return "short";
        }

        public static String overloaded(byte value) {
            return "byte";
        }

        public static String overloaded(float value) {
            return "float";
        }

        public static String overloaded(double value) {
            return "double";
        }

        public static String overloaded(char value) {
            return "char";
        }

        public static String overloaded(boolean value) {
            return "boolean";
        }

        public static String acceptLongArray(long[] values) {
            return "long:" + values.length;
        }

        public static String acceptBooleanArray(boolean[] values) {
            return "boolean:" + values.length;
        }

    }

    private static Object unwrap(Object value) {
        return value instanceof Wrapper wrapper ? wrapper.unwrap() : value;
    }

}
