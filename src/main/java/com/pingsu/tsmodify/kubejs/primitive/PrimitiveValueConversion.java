package com.pingsu.tsmodify.kubejs.primitive;

import dev.latvian.mods.rhino.Scriptable;
import dev.latvian.mods.rhino.Undefined;
import dev.latvian.mods.rhino.Wrapper;

import java.math.BigDecimal;
import java.math.BigInteger;

/** Shared conversion rules used by the script-facing primitive factories. */
final class PrimitiveValueConversion {
    private PrimitiveValueConversion() {
    }

    static Object unwrap(Object value) {
        Object current = value;
        for (int depth = 0; depth < 32 && current instanceof Wrapper wrapper; depth++) {
            Object next = wrapper.unwrap();
            if (next == current) {
                break;
            }
            current = next;
        }
        return current;
    }

    static boolean isMissing(Object value) {
        return value == null || value == Scriptable.NOT_FOUND || Undefined.isUndefined(value);
    }

    static BigInteger toBigInteger(Object value, String typeName) {
        Object unwrapped = unwrap(value);
        if (isMissing(unwrapped)) {
            throw invalid(typeName, value, "a finite integral number or an integer string");
        }

        if (unwrapped instanceof BigInteger integer) {
            return integer;
        }
        if (unwrapped instanceof BigDecimal decimal) {
            return exactInteger(decimal, typeName, value);
        }
        if (unwrapped instanceof Byte
                || unwrapped instanceof Short
                || unwrapped instanceof Integer
                || unwrapped instanceof Long) {
            return BigInteger.valueOf(((Number) unwrapped).longValue());
        }
        if (unwrapped instanceof Float || unwrapped instanceof Double) {
            double number = ((Number) unwrapped).doubleValue();
            if (!Double.isFinite(number)) {
                throw invalid(typeName, value, "a finite integral number");
            }
            try {
                return BigDecimal.valueOf(number).toBigIntegerExact();
            } catch (ArithmeticException failure) {
                throw invalid(typeName, value, "an integral number", failure);
            }
        }
        if (unwrapped instanceof Character character) {
            return BigInteger.valueOf(character);
        }
        if (unwrapped instanceof Number number) {
            try {
                return new BigDecimal(number.toString()).toBigIntegerExact();
            } catch (NumberFormatException | ArithmeticException failure) {
                throw invalid(typeName, value, "an integral number", failure);
            }
        }
        if (unwrapped instanceof CharSequence sequence) {
            return parseInteger(sequence.toString(), typeName, value);
        }

        throw invalid(typeName, value, "a finite integral number or an integer string");
    }

    static double toDouble(Object value, String typeName) {
        Object unwrapped = unwrap(value);
        if (isMissing(unwrapped)) {
            throw invalid(typeName, value, "a number");
        }

        if (unwrapped instanceof Number number) {
            return number.doubleValue();
        }
        if (unwrapped instanceof Character character) {
            return character;
        }
        if (unwrapped instanceof CharSequence sequence) {
            String text = sequence.toString().trim();
            if (text.isEmpty()) {
                throw invalid(typeName, value, "a number");
            }
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException failure) {
                throw invalid(typeName, value, "a number", failure);
            }
        }

        throw invalid(typeName, value, "a number");
    }

    static boolean toBoolean(Object value, String typeName) {
        Object unwrapped = unwrap(value);
        if (isMissing(unwrapped)) {
            throw invalid(typeName, value, "true, false, 0, or 1");
        }
        if (unwrapped instanceof Boolean bool) {
            return bool;
        }
        if (unwrapped instanceof CharSequence sequence) {
            String text = sequence.toString().trim();
            if (text.equalsIgnoreCase("true")) {
                return true;
            }
            if (text.equalsIgnoreCase("false")) {
                return false;
            }
            if (text.equals("0")) {
                return false;
            }
            if (text.equals("1")) {
                return true;
            }
            throw invalid(typeName, value, "true or false");
        }
        if (unwrapped instanceof Number) {
            try {
                BigInteger numeric = toBigInteger(unwrapped, typeName);
                if (BigInteger.ZERO.equals(numeric)) {
                    return false;
                }
                if (BigInteger.ONE.equals(numeric)) {
                    return true;
                }
            } catch (IllegalArgumentException ignored) {
                // Keep the boolean-specific error below for fractional or
                // non-finite numbers instead of reporting an integer parse error.
            }
        }

        throw invalid(typeName, value, "true, false, 0, or 1");
    }

    static char toChar(Object value, String typeName) {
        Object unwrapped = unwrap(value);
        if (isMissing(unwrapped)) {
            throw invalid(typeName, value, "a single UTF-16 character or a code unit number");
        }
        if (unwrapped instanceof Character character) {
            return character;
        }
        if (unwrapped instanceof CharSequence sequence) {
            String text = sequence.toString();
            if (text.length() == 1) {
                return text.charAt(0);
            }
            // Numeric strings are useful when a character code is read from a
            // configuration file, while ordinary multi-character strings stay invalid.
            try {
                BigInteger numeric = parseInteger(text, typeName, value);
                return checkedChar(numeric, typeName, value);
            } catch (IllegalArgumentException failure) {
                throw invalid(typeName, value, "a single UTF-16 character or a code unit number", failure);
            }
        }

        BigInteger numeric = toBigInteger(unwrapped, typeName);
        return checkedChar(numeric, typeName, value);
    }

    static IllegalArgumentException invalid(String typeName, Object value, String expected) {
        return invalid(typeName, value, expected, null);
    }

    static IllegalArgumentException invalid(
            String typeName,
            Object value,
            String expected,
            Throwable cause) {
        String message = typeName + " value must be " + expected + ", but was " + describe(value);
        return cause == null ? new IllegalArgumentException(message) : new IllegalArgumentException(message, cause);
    }

    private static BigInteger exactInteger(BigDecimal decimal, String typeName, Object original) {
        try {
            return decimal.toBigIntegerExact();
        } catch (ArithmeticException failure) {
            throw invalid(typeName, original, "an integral number", failure);
        }
    }

    private static BigInteger parseInteger(String raw, String typeName, Object original) {
        String text = raw.trim();
        if (text.isEmpty()) {
            throw invalid(typeName, original, "an integer string");
        }

        int sign = 1;
        int offset = 0;
        if (text.charAt(0) == '+' || text.charAt(0) == '-') {
            sign = text.charAt(0) == '-' ? -1 : 1;
            offset = 1;
        }
        if (offset == text.length()) {
            throw invalid(typeName, original, "an integer string");
        }

        String digits = text.substring(offset);
        int radix = 10;
        if (digits.startsWith("0x") || digits.startsWith("0X")) {
            radix = 16;
            digits = digits.substring(2);
        } else if (digits.startsWith("0b") || digits.startsWith("0B")) {
            radix = 2;
            digits = digits.substring(2);
        } else if (digits.startsWith("0o") || digits.startsWith("0O")) {
            radix = 8;
            digits = digits.substring(2);
        }

        if (digits.isEmpty()) {
            throw invalid(typeName, original, "an integer string");
        }
        try {
            if (radix != 10) {
                return BigInteger.valueOf(sign).multiply(new BigInteger(digits, radix));
            }
            return new BigDecimal(text).toBigIntegerExact();
        } catch (NumberFormatException | ArithmeticException failure) {
            throw invalid(typeName, original, "an integer string", failure);
        }
    }

    private static char checkedChar(BigInteger value, String typeName, Object original) {
        if (value.compareTo(BigInteger.ZERO) < 0 || value.compareTo(BigInteger.valueOf(Character.MAX_VALUE)) > 0) {
            throw invalid(typeName, original, "a code unit between 0 and 65535");
        }
        return (char) value.intValue();
    }

    private static String describe(Object value) {
        Object unwrapped = unwrap(value);
        if (isMissing(unwrapped)) {
            return "null or undefined";
        }

        String text;
        try {
            text = String.valueOf(unwrapped);
        } catch (RuntimeException ignored) {
            text = "<unprintable>";
        }
        if (text.length() > 120) {
            text = text.substring(0, 117) + "...";
        }
        return unwrapped.getClass().getName() + " (" + text + ")";
    }
}
