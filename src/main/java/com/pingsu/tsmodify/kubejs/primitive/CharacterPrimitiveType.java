package com.pingsu.tsmodify.kubejs.primitive;

/** Java UTF-16 {@code char} factory. */
public final class CharacterPrimitiveType extends AbstractPrimitiveType<Character> {
    public final Character MIN_VALUE = Character.MIN_VALUE;
    public final Character MAX_VALUE = Character.MAX_VALUE;

    CharacterPrimitiveType() {
        super("char", Character.class, char.class);
    }

    public Character minValue() {
        return Character.MIN_VALUE;
    }

    public Character maxValue() {
        return Character.MAX_VALUE;
    }

    @Override
    protected Character convert(Object value) {
        return PrimitiveValueConversion.toChar(value, name());
    }

    @Override
    protected boolean acceptsCharacterSequenceArray() {
        return true;
    }

}
