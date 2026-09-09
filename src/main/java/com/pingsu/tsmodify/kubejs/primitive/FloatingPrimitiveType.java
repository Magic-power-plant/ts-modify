package com.pingsu.tsmodify.kubejs.primitive;

import java.util.function.DoubleFunction;

/** Common implementation for Java float and double values. */
public class FloatingPrimitiveType<T extends Number> extends AbstractPrimitiveType<T> {
    private final double minimum;
    private final double maximum;
    private final DoubleFunction<T> factory;

    public final T MIN_VALUE;
    public final T MAX_VALUE;
    public final T NaN;
    public final T POSITIVE_INFINITY;
    public final T NEGATIVE_INFINITY;

    protected FloatingPrimitiveType(
            String typeName,
            Class<T> boxedClass,
            Class<?> primitiveClass,
            double minimum,
            double maximum,
            DoubleFunction<T> factory) {
        super(typeName, boxedClass, primitiveClass);
        this.minimum = minimum;
        this.maximum = maximum;
        this.factory = factory;
        this.MIN_VALUE = factory.apply(minimum);
        this.MAX_VALUE = factory.apply(maximum);
        this.NaN = factory.apply(Double.NaN);
        this.POSITIVE_INFINITY = factory.apply(Double.POSITIVE_INFINITY);
        this.NEGATIVE_INFINITY = factory.apply(Double.NEGATIVE_INFINITY);
    }

    public final T minValue() {
        return factory.apply(minimum);
    }

    public final T maxValue() {
        return factory.apply(maximum);
    }

    @Override
    protected final T convert(Object value) {
        return factory.apply(PrimitiveValueConversion.toDouble(value, name()));
    }
}
