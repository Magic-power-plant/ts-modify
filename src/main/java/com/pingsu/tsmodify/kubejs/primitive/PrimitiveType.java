package com.pingsu.tsmodify.kubejs.primitive;

/**
 * A script-facing factory for one Java primitive's boxed representation.
 *
 * <p>Rhino cannot keep a Java primitive as a JavaScript object. The factory
 * therefore returns the corresponding boxed value; Rhino will unbox it when a
 * Java method receives the primitive type.</p>
 *
 * @param <T> boxed Java value type
 */
public interface PrimitiveType<T> {
    T of(Object value);

    T valueOf(Object value);

    T parse(Object value);

    boolean is(Object value);

    Object array(Object values);

    Object arrayOf(Object... values);

    Class<T> boxedClass();

    Class<?> primitiveClass();

    String typeName();
}
