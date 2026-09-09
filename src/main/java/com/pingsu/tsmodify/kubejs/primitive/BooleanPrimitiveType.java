package com.pingsu.tsmodify.kubejs.primitive;

/** Java boolean factory with explicit script conversion rules. */
public final class BooleanPrimitiveType extends AbstractPrimitiveType<Boolean> {
    public final Boolean TRUE = Boolean.TRUE;
    public final Boolean FALSE = Boolean.FALSE;

    BooleanPrimitiveType() {
        super("boolean", Boolean.class, boolean.class);
    }

    public Boolean trueValue() {
        return Boolean.TRUE;
    }

    public Boolean falseValue() {
        return Boolean.FALSE;
    }

    @Override
    protected Boolean convert(Object value) {
        return PrimitiveValueConversion.toBoolean(value, name());
    }

}
