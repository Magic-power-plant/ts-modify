package com.pingsu.tsmodify.kubejs.primitive;

import dev.latvian.mods.rhino.Scriptable;
import dev.latvian.mods.rhino.Undefined;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

/** Base implementation for immutable script-facing primitive factories. */
public abstract class AbstractPrimitiveType<T> implements PrimitiveType<T> {
    private final String typeName;
    private final Class<T> boxedClass;
    private final Class<?> primitiveClass;

    protected AbstractPrimitiveType(String typeName, Class<T> boxedClass, Class<?> primitiveClass) {
        this.typeName = typeName;
        this.boxedClass = boxedClass;
        this.primitiveClass = primitiveClass;
    }

    @Override
    public final T of(Object value) {
        return convert(value);
    }

    @Override
    public final T valueOf(Object value) {
        return convert(value);
    }

    @Override
    public final T parse(Object value) {
        Object unwrapped = PrimitiveValueConversion.unwrap(value);
        if (PrimitiveValueConversion.isMissing(unwrapped) || !(unwrapped instanceof CharSequence)) {
            throw PrimitiveValueConversion.invalid(typeName, value, "a string representation of the value");
        }
        return convert(unwrapped);
    }

    @Override
    public final boolean is(Object value) {
        Object unwrapped = PrimitiveValueConversion.unwrap(value);
        return unwrapped != null && boxedClass.isInstance(unwrapped);
    }

    @Override
    public Object array(Object values) {
        List<Object> elements = collectElements(values);
        Object result = Array.newInstance(primitiveClass, elements.size());
        for (int index = 0; index < elements.size(); index++) {
            try {
                Array.set(result, index, convert(elements.get(index)));
            } catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException(
                        typeName + " array element " + index + " is invalid: " + failure.getMessage(),
                        failure);
            }
        }
        return result;
    }

    @Override
    public Object arrayOf(Object... values) {
        if (values == null) {
            throw PrimitiveValueConversion.invalid(typeName, null, "an array of values");
        }
        if (values.length == 1 && isArrayLike(values[0])) {
            return array(values[0]);
        }
        return array(values);
    }

    @Override
    public final Class<T> boxedClass() {
        return boxedClass;
    }

    @Override
    public final Class<?> primitiveClass() {
        return primitiveClass;
    }

    @Override
    public final String typeName() {
        return typeName;
    }

    @Override
    public String toString() {
        return typeName + " primitive type factory";
    }

    protected abstract T convert(Object value);

    protected final String name() {
        return typeName;
    }

    private List<Object> collectElements(Object values) {
        Object unwrapped = PrimitiveValueConversion.unwrap(values);
        if (PrimitiveValueConversion.isMissing(unwrapped)) {
            throw PrimitiveValueConversion.invalid(typeName, values, "an array or iterable of values");
        }

        if (unwrapped instanceof CharSequence && acceptsCharacterSequenceArray()) {
            CharSequence sequence = (CharSequence) unwrapped;
            List<Object> characters = new ArrayList<>(sequence.length());
            for (int index = 0; index < sequence.length(); index++) {
                characters.add(sequence.charAt(index));
            }
            return characters;
        }

        if (unwrapped.getClass().isArray()) {
            int length = Array.getLength(unwrapped);
            List<Object> elements = new ArrayList<>(length);
            for (int index = 0; index < length; index++) {
                elements.add(Array.get(unwrapped, index));
            }
            return elements;
        }

        if (unwrapped instanceof Collection<?> collection) {
            return new ArrayList<>(collection);
        }
        if (unwrapped instanceof Iterable<?> iterable) {
            List<Object> elements = new ArrayList<>();
            for (Object element : iterable) {
                elements.add(element);
            }
            return elements;
        }
        if (unwrapped instanceof Iterator<?> iterator) {
            List<Object> elements = new ArrayList<>();
            while (iterator.hasNext()) {
                elements.add(iterator.next());
            }
            return elements;
        }
        if (unwrapped instanceof Scriptable scriptable) {
            return collectScriptableElements(scriptable);
        }

        throw PrimitiveValueConversion.invalid(typeName, values, "an array or iterable of values");
    }

    private List<Object> collectScriptableElements(Scriptable scriptable) {
        Object lengthValue = scriptable.get(null, "length", scriptable);
        if (PrimitiveValueConversion.isMissing(lengthValue)) {
            throw PrimitiveValueConversion.invalid(typeName, scriptable, "an array or iterable of values");
        }

        long length;
        try {
            length = PrimitiveValueConversion.toBigInteger(lengthValue, typeName).longValueExact();
        } catch (IllegalArgumentException | ArithmeticException failure) {
            throw PrimitiveValueConversion.invalid(typeName, lengthValue, "an array length within the JVM range", failure);
        }
        if (length < 0 || length > Integer.MAX_VALUE) {
            throw PrimitiveValueConversion.invalid(typeName, lengthValue, "an array length within the JVM range");
        }

        List<Object> elements = new ArrayList<>((int) length);
        for (int index = 0; index < length; index++) {
            Object element = scriptable.get(null, index, scriptable);
            if (element == Scriptable.NOT_FOUND || Undefined.isUndefined(element)) {
                throw PrimitiveValueConversion.invalid(
                        typeName,
                        element,
                        "an array without missing values (index " + index + " is missing)");
            }
            elements.add(element);
        }
        return elements;
    }

    private boolean isArrayLike(Object value) {
        Object unwrapped = PrimitiveValueConversion.unwrap(value);
        return unwrapped != null
                && (unwrapped.getClass().isArray()
                || unwrapped instanceof Collection<?>
                || unwrapped instanceof Iterable<?>
                || unwrapped instanceof Iterator<?>
                || unwrapped instanceof Scriptable
                || (unwrapped instanceof CharSequence && acceptsCharacterSequenceArray()));
    }

    protected boolean acceptsCharacterSequenceArray() {
        return false;
    }
}
