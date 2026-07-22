package com.pingsu.tsmodify.probejs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;

public final class ProbeJSTypeInfoResolver {
    private static final Logger LOGGER = LoggerFactory.getLogger("TS Modify");

    private ProbeJSTypeInfoResolver() {
    }

    public static Type[] safeGenericInterfaces(Class<?> clazz) {
        try {
            return clazz.getGenericInterfaces();
        } catch (RuntimeException | LinkageError failure) {
            warnAboutRawFallback("interfaces", clazz, failure);
            return clazz.getInterfaces();
        }
    }

    public static Type safeGenericSuperclass(Class<?> clazz) {
        try {
            return clazz.getGenericSuperclass();
        } catch (RuntimeException | LinkageError failure) {
            warnAboutRawFallback("superclass", clazz, failure);
            return clazz.getSuperclass();
        }
    }

    private static void warnAboutRawFallback(String relation, Class<?> clazz, Throwable failure) {
        LOGGER.warn(
                "ProbeJS could not resolve generic {} for {}; using raw type metadata instead: {}",
                relation,
                clazz.getName(),
                failure.toString());
    }
}
