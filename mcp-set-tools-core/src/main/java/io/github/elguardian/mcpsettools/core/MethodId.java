package io.github.elguardian.mcpsettools.core;

import java.util.Comparator;
import java.util.Objects;
import java.util.StringJoiner;

import org.objectweb.asm.Type;

/**
 * Identifies a method by its JVM-level coordinates. {@code owner} is an internal class name
 * ({@code a/b/C$D}), {@code descriptor} a method descriptor ({@code (ILjava/lang/String;)V}).
 * Both the static analyzer and the agent produce ids in this form, so static and runtime call
 * graphs line up without any name mangling.
 */
public record MethodId(String owner, String name, String descriptor) implements Comparable<MethodId> {

    private static final Comparator<MethodId> ORDER = Comparator.comparing(MethodId::owner)
            .thenComparing(MethodId::name).thenComparing(MethodId::descriptor);

    public MethodId {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(descriptor, "descriptor");
    }

    public static MethodId of(String owner, String name, String descriptor) {
        return new MethodId(owner, name, descriptor);
    }

    /** Parses the {@link #toString()} form: {@code a/b/C.name(desc)ret}. */
    public static MethodId parse(String s) {
        int paren = s.indexOf('(');
        int dot = paren < 0 ? -1 : s.lastIndexOf('.', paren);
        if (paren < 0 || dot <= 0) {
            throw new IllegalArgumentException("Not a method id: " + s);
        }
        return new MethodId(s.substring(0, dot), s.substring(dot + 1, paren), s.substring(paren));
    }

    /** Binary class name of the owner ({@code a.b.C$D}). */
    public String className() {
        return owner.replace('/', '.');
    }

    public boolean isConstructor() {
        return name.equals("<init>");
    }

    public boolean isStaticInitializer() {
        return name.equals("<clinit>");
    }

    /** Readable Java-style form: {@code a.b.C.name(int,java.lang.String)}. */
    public String javaName() {
        return className() + "." + name + "(" + javaArguments() + ")";
    }

    /** Comma-separated Java names of the parameter types. */
    public String javaArguments() {
        StringJoiner j = new StringJoiner(",");
        for (Type t : Type.getArgumentTypes(descriptor)) {
            j.add(t.getClassName());
        }
        return j.toString();
    }

    @Override
    public int compareTo(MethodId o) {
        return ORDER.compare(this, o);
    }

    @Override
    public String toString() {
        return owner + "." + name + descriptor;
    }
}
