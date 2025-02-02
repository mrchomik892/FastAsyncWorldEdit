package com.boydti.fawe.util;

import org.jetbrains.annotations.NotNull;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Comparator;

public class ReflectionUtils {

    public static <T> T as(Class<T> t, Object o) {
        return t.isInstance(o) ? t.cast(o) : null;
    }

    public static void setAccessibleNonFinal(Field field) throws IllegalAccessException, NoSuchMethodException, InvocationTargetException {
        // let's make the field accessible
        field.setAccessible(true);

        // next we change the modifier in the Field instance to
        // not be final anymore, thus tricking reflection into
        // letting us modify the static final field
        if (Modifier.isFinal(field.getModifiers())) {
            try {
                Field lookupField = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
                lookupField.setAccessible(true);

                // blank out the final bit in the modifiers int
                ((MethodHandles.Lookup) lookupField.get(null))
                    .findSetter(Field.class, "modifiers", int.class)
                    .invokeExact(field, field.getModifiers() & ~Modifier.FINAL);
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }
    }

    public static void setFailsafeFieldValue(Field field, Object target, Object value) throws IllegalAccessException, NoSuchMethodException, InvocationTargetException {
        setAccessibleNonFinal(field);
        field.set(target, value);
    }

    public static Object getHandle(Object wrapper) {
        final Method getHandle = makeMethod(wrapper.getClass(), "getHandle");
        return callMethod(getHandle, wrapper);
    }

    //Utils
    public static Method makeMethod(Class<?> clazz, String methodName, Class<?>... parameters) {
        try {
            return clazz.getDeclaredMethod(methodName, parameters);
        } catch (NoSuchMethodException ex) {
            return null;
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> T callMethod(Method method, Object instance, Object... parameters) {
        if (method == null) {
            throw new RuntimeException("No such method");
        }
        method.setAccessible(true);
        try {
            return (T) method.invoke(instance, parameters);
        } catch (InvocationTargetException ex) {
            throw new RuntimeException(ex.getCause());
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    public static Field[] sortFields(Field[] fields) {
        Arrays.sort(fields, Comparator.comparing(Field::getName));
        return fields;
    }

    public static <T extends AccessibleObject> T setAccessible(T ao) {
        ao.setAccessible(true);
        return ao;
    }

    @SuppressWarnings("unchecked")
    public static <T> T getField(@NotNull Field field, Object instance) {
        field.setAccessible(true);
        try {
            return (T) field.get(instance);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    public static Class<?> getClass(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException ex) {
            return null;
        }
    }

    public static <T> Class<? extends T> getClass(String name, Class<T> superClass) {
        try {
            return Class.forName(name).asSubclass(superClass);
        } catch (ClassCastException | ClassNotFoundException ex) {
            return null;
        }
    }
}
