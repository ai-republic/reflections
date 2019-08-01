package com.airepublic.reflections;

import java.lang.StackWalker.Option;
import java.lang.annotation.Annotation;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.lang.module.ResolvedModule;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Reflection tools to discover classes on module- or classpath, find annotated classes and general
 * reflection functionality.
 * 
 * @author Torsten Oltmanns
 *
 */
public class Reflections {
    private static final Logger LOG = Logger.getGlobal();
    public static final List<String> ALL_PACKAGES = new ArrayList<>();
    public static final List<ModuleReference> ALL_MODULES = new ArrayList<>();
    public static final List<Class<?>> ALL_CLASSES = new ArrayList<>();
    public static final List<ModuleReference> SYSTEM_MODULES = new ArrayList<>();
    public static final List<ModuleReference> NON_SYSTEM_MODULES = new ArrayList<>();

    static {
        final Class<?>[] callStack = getCallStack();

        final List<Entry<ModuleReference, ModuleLayer>> moduleRefs = findModuleRefs(callStack);
        // Split module refs into system and non-system modules based on module name
        for (final Entry<ModuleReference, ModuleLayer> m : moduleRefs) {
            (isSystemModule(m.getKey()) ? SYSTEM_MODULES : NON_SYSTEM_MODULES).add(m.getKey());
            ALL_PACKAGES.addAll(m.getKey().descriptor().packages());
        }

        // List system modules
        LOG.fine("\nSYSTEM MODULES:\n");
        for (final ModuleReference ref : SYSTEM_MODULES) {
            LOG.fine("  " + ref.descriptor().name());
        }

        // Show info for non-system modules
        LOG.fine("\nNON-SYSTEM MODULES:");
        for (final ModuleReference ref : NON_SYSTEM_MODULES) {
            LOG.fine("\n  " + ref.descriptor().name());
            LOG.fine("\tVersion: " + ref.descriptor().toNameAndVersion());
            LOG.fine("\tPackages: " + ref.descriptor().packages());
            // LOG.fine(" ClassLoader: " + layer.findLoader(ref.descriptor().name()));
            final Optional<URI> location = ref.location();

            if (location.isPresent()) {
                LOG.fine("\tLocation: " + location.get());
            }

            try (final ModuleReader moduleReader = ref.open()) {
                final Stream<String> stream = moduleReader.list();

                stream.forEach(s -> {
                    LOG.fine("\t\tFile: " + s);
                    if (s.endsWith(".class")) {
                        final String className = s.replace(".class", "").replace('/', '.');

                        try {
                            ALL_CLASSES.add(Class.forName(className));
                        } catch (final Throwable e) {
                            LOG.info("Error: " + e.getMessage());
                        }
                    }
                });
            } catch (final Exception e) {
                LOG.info("Error: " + e.getMessage());
            }
        }

        for (final String pkg : ALL_PACKAGES) {
            LOG.fine("\t" + pkg);
        }
    }

    /**
     * Class to get classes in the call stack.
     * 
     * @author Torsten Oltmanns
     *
     */
    public final static class CallerResolver extends SecurityManager {
        /**
         * Get classes in the call stack.
         * 
         * @return an array of classes from the call stack
         */
        @Override
        public Class<?>[] getClassContext() {
            return super.getClassContext();
        }
    }


    /**
     * Recursively find the topological sort order of ancestral layers.
     * 
     * @param layer the {@link ModuleLayer}
     * @param visited the set of {@link ModuleLayer}s which have already been scanned
     * @param layersOut the queue of ordered {@link ModuleLayer}
     */
    public static void findLayerOrder(final ModuleLayer layer, final Set<ModuleLayer> visited, final Deque<ModuleLayer> layersOut) {
        if (visited.add(layer) && layer != null && layer.parents() != null) {
            for (final ModuleLayer parent : layer.parents()) {
                findLayerOrder(parent, visited, layersOut);
            }
            layersOut.push(layer);
        }
    }


    /**
     * Get ModuleReferences from a Class reference.
     * 
     * @param callStack the array of classes from the callstack
     * @return the list containing mapping of {@link ModuleReference} to {@link ModuleLayer}
     */
    public static List<Entry<ModuleReference, ModuleLayer>> findModuleRefs(final Class<?>[] callStack) {
        final Deque<ModuleLayer> layerOrder = new ArrayDeque<>();
        final Set<ModuleLayer> visited = new HashSet<>();

        for (final Class<?> element : callStack) {
            final ModuleLayer layer = element.getModule().getLayer();
            findLayerOrder(layer, visited, layerOrder);
        }

        final Set<ModuleReference> addedModules = new HashSet<>();
        final List<Entry<ModuleReference, ModuleLayer>> moduleRefs = new ArrayList<>();

        for (final ModuleLayer layer : layerOrder) {
            final Set<ResolvedModule> modulesInLayerSet = layer.configuration().modules();
            final List<Entry<ModuleReference, ModuleLayer>> modulesInLayer = new ArrayList<>();

            for (final ResolvedModule module : modulesInLayerSet) {
                modulesInLayer.add(new SimpleEntry<>(module.reference(), layer));
            }

            // Sort modules in layer by name for consistency
            Collections.sort(modulesInLayer, (e1, e2) -> e1.getKey().descriptor().name().compareTo(e2.getKey().descriptor().name()));

            // To be safe, dedup ModuleReferences, in case a module occurs in multiple
            // layers and reuses its ModuleReference (no idea if this can happen)
            for (final Entry<ModuleReference, ModuleLayer> m : modulesInLayer) {
                if (addedModules.add(m.getKey())) {
                    moduleRefs.add(m);
                }
            }
        }

        return moduleRefs;
    }


    /**
     * Get the classes in the call stack.
     * 
     * @return the array of classes in the callstack
     */
    public static Class<?>[] getCallStack() {
        // Try StackWalker (JDK 9+)
        final PrivilegedAction<Class<?>[]> stackWalkerAction = () -> {
            final List<Class<?>> stackFrameClasses = new ArrayList<>();
            StackWalker.getInstance(Option.RETAIN_CLASS_REFERENCE).forEach(sf -> stackFrameClasses.add(sf.getDeclaringClass()));
            return stackFrameClasses.toArray(new Class<?>[0]);
        };

        try {
            // Try with doPrivileged()
            return AccessController.doPrivileged(stackWalkerAction);
        } catch (final Exception e) {
        }

        try {
            // Try without doPrivileged()
            return stackWalkerAction.run();
        } catch (final Exception e) {
        }

        final CallerResolver callerResolver = new CallerResolver();
        // Try SecurityManager
        final PrivilegedAction<Class<?>[]> callerResolverAction = () -> callerResolver.getClassContext();
        try {
            // Try with doPrivileged()
            return AccessController.doPrivileged(callerResolverAction);
        } catch (final Exception e) {
        }

        try {
            // Try without doPrivileged()
            return callerResolverAction.run();
        } catch (final Exception e) {
        }

        // As a fallback, use getStackTrace() to try to get the call stack
        try {
            throw new Exception();
        } catch (final Exception e) {
            final List<Class<?>> classes = new ArrayList<>();

            for (final StackTraceElement elt : e.getStackTrace()) {
                try {
                    classes.add(Class.forName(elt.getClassName()));
                } catch (final Throwable e2) {
                    // Ignore
                }
            }

            if (classes.size() > 0) {
                return classes.toArray(new Class<?>[0]);
            } else {
                // Last-ditch effort -- include just this class in the call stack
                return new Class<?>[] { Reflections.class };
            }
        }
    }


    /**
     * Return true if the given module name is a system module. There can be system modules in
     * layers above the boot layer.
     * 
     * @param moduleReference the {@link ModuleReference}
     * @return true if its a system module otherwise false
     */
    public static boolean isSystemModule(final ModuleReference moduleReference) {
        final String name = moduleReference.descriptor().name();

        if (name == null) {
            return false;
        }

        return name.startsWith("java.") || name.startsWith("jdk.") || name.startsWith("javafx.") || name.startsWith("oracle.");
    }


    /**
     * Finds classes on the module-/classpath with the specified annotation.
     * 
     * @param annotation the {@link Annotation} class
     * @return the set of annotated classes
     */
    public static Set<Class<?>> findClassesWithAnnotation(final Class<? extends Annotation> annotation) {
        return ALL_CLASSES.parallelStream().filter(c -> getClassWithAnnotation(c, annotation) != null).collect(Collectors.toSet());
    }


    /**
     * Finds classes on the module-/classpath with the specified annotations.
     * 
     * @param annotations the {@link Annotation} classes
     * @return the set of annotated classes
     */
    @SafeVarargs
    public static Set<Class<?>> findClassesWithAnnotations(final Class<? extends Annotation>... annotations) {
        return ALL_CLASSES.parallelStream().filter(c -> Stream.of(annotations).anyMatch(annotation -> getClassWithAnnotation(c, annotation) != null)).collect(Collectors.toSet());
    }


    /**
     * Finds classes on the module-/classpath with a annotated method with the specified annotation.
     * 
     * @param annotations the {@link Annotation} classes
     * @return the set of annotated classes
     */
    @SafeVarargs
    public static Set<Class<?>> findClassesWithMethodAnnotations(final Class<? extends Annotation>... annotations) {
        return ALL_CLASSES.parallelStream().filter(c -> !getAnnotatedMethods(c, annotations).isEmpty()).collect(Collectors.toSet());
    }


    /**
     * Gets the actual class or interface which is annotated with the specified {@link Annotation}.
     * 
     * @param clazz the class to check
     * @param annotation the {@link Annotation} class
     * @return the annotated class
     */
    public static Class<?> getClassWithAnnotation(final Class<?> clazz, final Class<? extends Annotation> annotation) {
        if (clazz.isAnnotationPresent(annotation)) {
            return clazz;
        }

        for (final Class<?> intf : clazz.getInterfaces()) {
            if (intf.isAnnotationPresent(annotation)) {
                return intf;
            }
        }

        final Class<?> superClass = clazz.getSuperclass();

        if (superClass != Object.class && superClass != null) {
            return getClassWithAnnotation(superClass, annotation);
        }

        return null;
    }


    /**
     * Gets the value of a {@link Field} that has the specified annotation.
     * 
     * @param obj the object
     * @param annotationClass the {@link Annotation} class
     * @return the value of the {@link Field}
     */
    public static Object getAnnotatedValue(final Object obj, final Class<? extends Annotation> annotationClass) {
        try {
            for (final Field f : getFieldsOfAllSuperclasses(obj.getClass())) {
                if (f.isAnnotationPresent(annotationClass)) {
                    f.setAccessible(true);
                    return f.get(obj);
                }
            }
        } catch (final Exception e) {
            e.printStackTrace();
            LOG.log(Level.SEVERE, "Error getting @" + annotationClass.getSimpleName() + " annotation value on class: " + obj.getClass(), e);
        }

        throw new IllegalArgumentException("No @" + annotationClass.getSimpleName() + " annotation found on class: " + obj.getClass());
    }


    /**
     * Gets all {@link Field}s in the object which are annotated with the specified
     * {@link Annotation} class.
     * 
     * @param obj the object
     * @param annotationClass the {@link Annotation} class
     * @return the set of {@link Field}s
     */
    public static Set<Field> getAnnotatedFields(final Object obj, final Class<? extends Annotation> annotationClass) {
        final Set<Field> fields = new HashSet<>();
        try {
            for (final Field f : getFieldsOfAllSuperclasses(obj.getClass())) {
                if (f.isAnnotationPresent(annotationClass)) {
                    f.setAccessible(true);
                    fields.add(f);
                }
            }
        } catch (final Exception e) {
            e.printStackTrace();
            LOG.log(Level.SEVERE, "Error getting @" + annotationClass.getSimpleName() + " annotation value on class: " + obj.getClass(), e);
        }

        return fields;
    }


    /**
     * Gets all methods of a class which are annotated with the specified annotation including those
     * of implemented interfaces and superclasses.
     * 
     * @param clazz the class to search
     * @param annotationClasses the {@link Annotation}s to find
     * @return a set of all annotated methods
     */
    @SafeVarargs
    public static Set<Method> getAnnotatedMethods(final Class<?> clazz, final Class<? extends Annotation>... annotationClasses) {
        try {
            return Stream.of(clazz.getMethods()).filter(m -> Stream.of(annotationClasses).anyMatch(m::isAnnotationPresent)).collect(Collectors.toSet());
        } catch (final Throwable e) {
            LOG.log(Level.SEVERE, "Error finding " + annotationClasses + " annotation methods on class: " + clazz, e);
            return Collections.emptySet();
        }
    }


    /**
     * Gets a collection of all the fields of the specified class and its superclasses.
     * 
     * @param clazz the class
     * @return all the fields of the specified class and its superclasses
     */
    public static Set<Field> getFieldsOfAllSuperclasses(final Class<?> clazz) {
        return getFieldsOfAllSuperclasses(clazz, new LinkedHashSet<Field>());
    }


    /**
     * Gets a collection of all the fields of the specified class and its superclasses.
     * 
     * @param clazz the class
     * @param classes the set of fields to keep during recursion
     * @return all the fields of the specified class and its superclasses
     */
    public static Set<Field> getFieldsOfAllSuperclasses(final Class<?> clazz, Set<Field> classes) {
        for (final Field field : clazz.getDeclaredFields()) {
            if ((field.getModifiers() & 0x18) != (Modifier.FINAL | Modifier.STATIC)) {
                classes.add(field);
            }
        }

        if (clazz.getSuperclass() != null) {
            classes = getFieldsOfAllSuperclasses(clazz.getSuperclass(), classes);
        }

        return classes;
    }


    /**
     * Sets the value of a {@link Field} annotated with the specified {@link Annotation} class.
     * 
     * @param obj the object
     * @param value the value
     * @param annotationClass the {@link Annotation} class
     */
    public static void setAnnotatedValue(final Object obj, final Object value, final Class<? extends Annotation> annotationClass) {
        try {
            for (final Field f : getFieldsOfAllSuperclasses(obj.getClass())) {
                if (f.isAnnotationPresent(annotationClass)) {
                    f.setAccessible(true);
                    f.set(obj, value);
                    return;
                }
            }
        } catch (final Exception e) {
            e.printStackTrace();
            LOG.log(Level.SEVERE, "Error setting @" + annotationClass.getSimpleName() + " annotation value on class: " + obj.getClass(), e);
        }

        throw new IllegalArgumentException("No @" + annotationClass.getSimpleName() + " annotation found on class: " + obj.getClass());
    }


    /**
     * Converts the specified value to the correct numeric type by checking the fields type. In case
     * the value resembles a {@link Number} it will convert it to the fields numeric type, e.g. if
     * the value is Integer and the field-type is Long, then the Integer will be converted to Long.
     *
     * @param field the field
     * @param value the value
     * @return the converted value or the value itself if no conversion was done
     */
    public static Object convertToCorrectType(final Field field, final Object value) {
        if (value instanceof Number) {
            if (field.getType() == Integer.class) {
                return ((Number) value).intValue();
            } else if (field.getType() == Long.class) {
                return ((Number) value).longValue();
            } else if (field.getType() == Float.class) {
                return ((Number) value).floatValue();
            } else if (field.getType() == Double.class) {
                return ((Number) value).doubleValue();
            } else if (field.getType() == Short.class) {
                return ((Number) value).shortValue();
            } else if (field.getType() == Byte.class) {
                return ((Number) value).byteValue();
            }
        } else if (field.getType().isEnum()) {
            if (value instanceof String) {
            }
        }

        return value;
    }


    /**
     * Gets the values of all fields of the specified object and all its superclasses.
     * 
     * @param object the object
     * @return the map of fieldnames and their values
     * @throws Exception if an exception occurs during reading
     */
    public static Map<String, Object> getFieldValues(final Object object) throws Exception {
        final Map<String, Object> fields = new LinkedHashMap<>();
        // parse all fields of the object
        for (final Field f : getFieldsOfAllSuperclasses(object.getClass())) {
            // exclude all final fields
            if ((f.getModifiers() & 0x18) != (Modifier.FINAL | Modifier.STATIC)) {
                f.setAccessible(true);

                fields.put(f.getName(), f.get(object));
            }
        }

        return fields;
    }


    /**
     * Sets the values of all fields of the specified object and all its superclasses.
     * 
     * @param object the object
     * @param fields the map of fieldnames and their values
     * @throws Exception if an exception occurs during writing
     */
    public static void setFieldValues(final Object object, final Map<String, Object> fields) throws Exception {
        // parse all fields of the object
        for (final Field f : getFieldsOfAllSuperclasses(object.getClass())) {
            // exclude all final fields
            if ((f.getModifiers() & 0x18) != (Modifier.FINAL | Modifier.STATIC)) {
                f.setAccessible(true);

                f.set(object, fields.get(f.getName()));
            }
        }
    }


    /**
     * Sets the value of the specified field.
     * 
     * @param obj the object
     * @param value the value
     * @param fieldName the field name
     */
    public static void setFieldValue(final Object obj, final Object value, final String fieldName) {
        try {
            for (final Field f : getFieldsOfAllSuperclasses(obj.getClass())) {
                if (f.getName().equals(fieldName)) {
                    f.setAccessible(true);
                    f.set(obj, value);
                    return;
                }
            }
        } catch (final Exception e) {
            e.printStackTrace();
            LOG.log(Level.SEVERE, "Error setting field " + fieldName + " value on class: " + obj.getClass(), e);
        }

        throw new IllegalArgumentException("No field " + fieldName + " found on class: " + obj.getClass());
    }


    /**
     * Gets the value of the specified field.
     * 
     * @param obj the object
     * @param fieldName the field name return the value or null
     * @return the value
     */
    public static Object getFieldValue(final Object obj, final String fieldName) {
        try {
            for (final Field f : getFieldsOfAllSuperclasses(obj.getClass())) {
                if (f.getName().contentEquals(fieldName)) {
                    f.setAccessible(true);
                    return f.get(obj);
                }
            }
        } catch (final Exception e) {
            e.printStackTrace();
            LOG.log(Level.SEVERE, "Error getting field " + fieldName + " value on class: " + obj.getClass(), e);
        }

        throw new IllegalArgumentException("No field " + fieldName + " found on class: " + obj.getClass());
    }


    /**
     * Gets the value of a class {@link Annotation}'s parameter.
     * 
     * @param clazz the class
     * @param annotationClass the {@link Annotation} class
     * @param parameterName the parameter name of the {@link Annotation}
     * @return the value as String
     * @throws Exception if the value cannot be cast to String
     */
    public static String getClassAnnotationParameterValue(final Class<?> clazz, final Class<? extends Annotation> annotationClass, final String parameterName) throws Exception {
        final Annotation annotation = clazz.getAnnotation(annotationClass);
        return String.class.cast(annotation.getClass().getMethod(parameterName, new Class<?>[] {}).invoke(annotation, new Object[] {}));

    }


    /**
     * Gets the value of a method {@link Annotation}'s parameter.
     * 
     * @param method the {@link Method}
     * @param annotationClass the {@link Annotation} class
     * @param parameterName the parameter name of the {@link Annotation}
     * @return the value as String
     * @throws Exception if the value cannot be cast to String
     */
    public static String getMethodAnnotationParameterValue(final Method method, final Class<? extends Annotation> annotationClass, final String parameterName) throws Exception {
        if (method.isAnnotationPresent(annotationClass)) {
            final Annotation annotation = method.getAnnotation(annotationClass);

            return String.class.cast(annotation.getClass().getMethod(parameterName, new Class<?>[] {}).invoke(annotation, new Object[] {}));
        }

        return null;
    }


    /**
     * Finds classes extending the specified superclass.
     * 
     * @param <T> the superclass type
     * @param superClass the superclass class
     * @return a set of classes extending the superclass
     */
    @SuppressWarnings("unchecked")
    public static <T> Set<? extends Class<? extends T>> findClassesExtending(final Class<T> superClass) {
        return ALL_CLASSES.parallelStream().filter(c -> superClass.isAssignableFrom(c)).map(c -> (Class<? extends T>) c).collect(Collectors.toSet());
    }

}