package com.pingsu.tsmodify.kubejs.primitive;

import java.math.BigInteger;
import java.util.function.Function;

/** Common implementation for Java byte, short, int, and long values. */
public class IntegralPrimitiveType<T extends Number> extends AbstractPrimitiveType<T> {
    private final BigInteger minimum;
    private final BigInteger maximum;
    private final Function<BigInteger, T> factory;

    public final T MIN_VALUE;
    public final T MAX_VALUE;

    protected IntegralPrimitiveType(
            String typeName,
            Class<T> boxedClass,
            Class<?> primitiveClass,
            long minimum,
            long maximum,
            Function<BigInteger, T> factory) {
        this(typeName, boxedClass, primitiveClass, BigInteger.valueOf(minimum), BigInteger.valueOf(maximum), factory);
    }

    protected IntegralPrimitiveType(
            String typeName,
            Class<T> boxedClass,
            Class<?> primitiveClass,
            BigInteger minimum,
            BigInteger maximum,
            Function<BigInteger, T> factory) {
        super(typeName, boxedClass, primitiveClass);
        this.minimum = minimum;
        this.maximum = maximum;
        this.factory = factory;
        this.MIN_VALUE = factory.apply(minimum);
        this.MAX_VALUE = factory.apply(maximum);
    }

    public final T minValue() {
        return factory.apply(minimum);
    }

    public final T maxValue() {
        return factory.apply(maximum);
    }

    public final int bitSize() {
        return maximum.bitLength() + 1;
    }

    @Override
    protected final T convert(Object value) {
        BigInteger integer = PrimitiveValueConversion.toBigInteger(value, name());
        if (integer.compareTo(minimum) < 0 || integer.compareTo(maximum) > 0) {
            throw PrimitiveValueConversion.invalid(
                    name(),
                    value,
                    "a value between " + minimum + " and " + maximum);
        }
        return factory.apply(integer);
    }
}
