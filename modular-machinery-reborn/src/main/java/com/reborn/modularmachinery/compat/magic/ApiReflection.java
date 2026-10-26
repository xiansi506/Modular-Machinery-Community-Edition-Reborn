package com.reborn.modularmachinery.compat.magic;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Public optional API methods only; no Minecraft member names are reflected. */
public final class ApiReflection {
    private ApiReflection() {}
    private record Key(Class<?> owner, String name, List<Class<?>> args) {}
    private static final Map<Key, Method> METHODS = new ConcurrentHashMap<>();
    public static Class<?> type(String name) {
        try { return Class.forName(name); } catch (ClassNotFoundException error) { throw new IllegalStateException("Missing optional magic API " + name, error); }
    }
    public static Object field(Class<?> type, String name) {
        try { return type.getField(name).get(null); } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }
    public static Object call(Class<?> type, Object instance, String name, Class<?>[] parameters, Object... args) {
        try {
            Method method = METHODS.computeIfAbsent(new Key(type, name, List.of(parameters)), key -> {
                try { return key.owner.getMethod(key.name, parameters); } catch (ReflectiveOperationException error) { throw new IllegalStateException("Unsupported optional magic API " + key.owner.getName() + "." + key.name, error); }
            });
            return method.invoke(instance, args);
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof RuntimeException cause) throw cause;
            throw new IllegalStateException(error.getCause());
        } catch (IllegalAccessException error) { throw new IllegalStateException(error); }
    }
    public static Object call(Object instance, String name, Class<?>[] parameters, Object... args) {
        return call(instance.getClass(), instance, name, parameters, args);
    }
    @SuppressWarnings({"unchecked", "rawtypes"}) public static Object constant(Class<?> type, String name) { return Enum.valueOf((Class) type, name.toUpperCase(Locale.ROOT)); }
}
