package com.pingsu.tsmodify.kubejs;

/**
 * Formats the class-name suffix that KubeJS appends to console lines for
 * non-simple values, so it matches the body printed by {@code console.log(x.class)}.
 */
public final class ConsoleJSClassFormatter {

    private ConsoleJSClassFormatter() {
    }

    /**
     * Returns the same text KubeJS would print as the main body of
     * {@code console.log(value.class)}, i.e. {@link Class#toString()}.
     */
    public static String formatClassSuffix(Class<?> cls) {
        if (cls == null) {
            return "null";
        }
        return cls.toString();
    }
}
