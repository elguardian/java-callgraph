package io.github.elguardian.mcpsettools.stat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Type hierarchy of all classes read by the analysis, completed on demand with class headers
 * from the running JDK. Implements JVMS method resolution (§5.4.3.3–4) and selection (§5.4.6),
 * approximated: package-private override rules and maximally-specific conflicts are not
 * modeled — the first matching default method wins.
 */
public final class ClassHierarchy {

    private static final String OBJECT = "java/lang/Object";

    private final Map<String, ClassInfo> classes = new HashMap<>();
    private final Map<String, Set<String>> directSubtypes = new HashMap<>();
    private final Set<String> missing = new TreeSet<>();
    private final boolean jdkFallback;

    ClassHierarchy(boolean jdkFallback) {
        this.jdkFallback = jdkFallback;
    }

    /** Adds a class; returns false when a class with the same name is already known. */
    boolean add(ClassInfo c) {
        if (classes.putIfAbsent(c.name(), c) != null) {
            return false;
        }
        if (c.superName() != null) {
            directSubtypes.computeIfAbsent(c.superName(), k -> new HashSet<>()).add(c.name());
        }
        for (String i : c.interfaces()) {
            directSubtypes.computeIfAbsent(i, k -> new HashSet<>()).add(c.name());
        }
        return true;
    }

    /** Looks a class up by internal name, loading it from the JDK if needed; null if unknown. */
    public ClassInfo get(String name) {
        if (name == null || name.startsWith("[")) {
            return null;
        }
        ClassInfo c = classes.get(name);
        if (c == null && !missing.contains(name)) {
            c = jdkFallback ? ClassPathReader.fromJdk(name) : null;
            if (c == null) {
                missing.add(name);
            } else {
                add(c);
            }
        }
        return c;
    }

    public Collection<ClassInfo> classes() {
        return Collections.unmodifiableCollection(classes.values());
    }

    /** Classes referenced but found neither in the inputs nor in the JDK. */
    public Set<String> missingClasses() {
        return Collections.unmodifiableSet(missing);
    }

    /** Reflexive, transitive subtype test over superclasses and interfaces. */
    public boolean isSubtypeOf(String type, String supertype) {
        if (type.equals(supertype) || supertype.equals(OBJECT)) {
            return true;
        }
        Deque<String> work = new ArrayDeque<>(List.of(type));
        Set<String> seen = new HashSet<>();
        while (!work.isEmpty()) {
            String t = work.pop();
            if (!seen.add(t)) {
                continue;
            }
            if (t.equals(supertype)) {
                return true;
            }
            ClassInfo c = get(t);
            if (c != null) {
                if (c.superName() != null) {
                    work.push(c.superName());
                }
                c.interfaces().forEach(work::push);
            }
        }
        return false;
    }

    /** {@code type} and all its known transitive subtypes. */
    public Set<ClassInfo> subtypes(String type) {
        Set<ClassInfo> result = new LinkedHashSet<>();
        Deque<String> work = new ArrayDeque<>(List.of(type));
        Set<String> seen = new HashSet<>();
        while (!work.isEmpty()) {
            String t = work.pop();
            if (!seen.add(t)) {
                continue;
            }
            ClassInfo c = get(t);
            if (c != null) {
                result.add(c);
            }
            work.addAll(directSubtypes.getOrDefault(t, Set.of()));
        }
        return result;
    }

    /** All superclasses and superinterfaces of {@code type} (not including itself). */
    public List<ClassInfo> supertypes(String type) {
        List<ClassInfo> result = new ArrayList<>();
        Deque<String> work = new ArrayDeque<>();
        Set<String> seen = new HashSet<>(List.of(type));
        ClassInfo start = get(type);
        if (start == null) {
            return result;
        }
        pushSupers(start, work);
        while (!work.isEmpty()) {
            String t = work.removeFirst();
            if (!seen.add(t)) {
                continue;
            }
            ClassInfo c = get(t);
            if (c != null) {
                result.add(c);
                pushSupers(c, work);
            }
        }
        return result;
    }

    private static void pushSupers(ClassInfo c, Deque<String> work) {
        if (c.superName() != null) {
            work.addLast(c.superName());
        }
        c.interfaces().forEach(work::addLast);
    }

    /**
     * Resolves a symbolic method reference the way the JVM links it: the declaring class
     * chain first, then superinterfaces (non-abstract defaults preferred). Null if unknown.
     */
    public MethodInfo resolve(String owner, String name, String descriptor) {
        ClassInfo c = get(owner);
        if (c == null) {
            return null;
        }
        if (c.isInterface()) {
            MethodInfo m = c.method(name, descriptor);
            if (m != null) {
                return m;
            }
            ClassInfo object = get(OBJECT);
            m = object == null ? null : object.method(name, descriptor);
            if (m != null && !m.isStatic() && !m.isPrivate()) {
                return m;
            }
            return fromInterfaces(c, name, descriptor, false);
        }
        for (ClassInfo k = c; k != null; k = get(k.superName())) {
            MethodInfo m = k.method(name, descriptor);
            if (m != null) {
                return m;
            }
        }
        return fromInterfaces(c, name, descriptor, false);
    }

    /**
     * Selects the method a virtual call runs when the receiver's runtime class is
     * {@code receiverClass}. Null if there is no concrete implementation.
     */
    public MethodInfo dispatch(String receiverClass, String name, String descriptor) {
        ClassInfo c = get(receiverClass);
        if (c == null) {
            return null;
        }
        for (ClassInfo k = c; k != null; k = get(k.superName())) {
            MethodInfo m = k.method(name, descriptor);
            if (m != null && !m.isStatic() && !m.isPrivate()) {
                return m.isAbstract() ? null : m;
            }
        }
        return fromInterfaces(c, name, descriptor, true);
    }

    private MethodInfo fromInterfaces(ClassInfo c, String name, String descriptor, boolean concreteOnly) {
        MethodInfo abstractCandidate = null;
        for (ClassInfo s : supertypes(c.name())) {
            if (!s.isInterface()) {
                continue;
            }
            MethodInfo m = s.method(name, descriptor);
            if (m == null || m.isStatic() || m.isPrivate()) {
                continue;
            }
            if (!m.isAbstract()) {
                return m;
            }
            if (abstractCandidate == null) {
                abstractCandidate = m;
            }
        }
        return concreteOnly ? null : abstractCandidate;
    }
}
