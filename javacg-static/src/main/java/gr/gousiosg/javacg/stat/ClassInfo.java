package gr.gousiosg.javacg.stat;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

/** A class known to the analysis. For {@link ClassScope#APP} classes {@link #node()} holds the bodies. */
public final class ClassInfo {

    private final String name;
    private final String superName;
    private final List<String> interfaces;
    private final int access;
    private final ClassScope scope;
    private final String sourceFile;
    private final String origin;
    private final ClassNode node;
    private final Set<String> referencedClasses;
    private final Map<String, MethodInfo> methods = new LinkedHashMap<>();

    ClassInfo(ClassNode cn, ClassScope scope, String origin, Set<String> referencedClasses) {
        this.name = cn.name;
        this.superName = cn.superName;
        this.interfaces = List.copyOf(cn.interfaces);
        this.access = cn.access;
        this.scope = scope;
        this.sourceFile = cn.sourceFile;
        this.origin = origin;
        this.node = scope == ClassScope.APP ? cn : null;
        this.referencedClasses = referencedClasses;
        cn.methods.forEach(m -> methods.put(m.name + m.desc,
                new MethodInfo(this, m.name, m.desc, m.access, scope == ClassScope.APP ? m : null)));
    }

    /** Internal name, e.g. {@code a/b/C$D}. */
    public String name() {
        return name;
    }

    /** Internal name of the superclass; null for {@code java/lang/Object} and module-info. */
    public String superName() {
        return superName;
    }

    public List<String> interfaces() {
        return interfaces;
    }

    public int access() {
        return access;
    }

    public ClassScope scope() {
        return scope;
    }

    /** The {@code SourceFile} attribute, or null. */
    public String sourceFile() {
        return sourceFile;
    }

    /** The input (jar, directory, nested archive path) the class was read from. */
    public String origin() {
        return origin;
    }

    /** Full class node with method bodies; only for {@link ClassScope#APP}, otherwise null. */
    public ClassNode node() {
        return node;
    }

    /** Classes named in the constant pool (binary names); only for {@link ClassScope#APP}. */
    public Set<String> referencedClasses() {
        return referencedClasses == null ? Set.of() : Collections.unmodifiableSet(referencedClasses);
    }

    public Collection<MethodInfo> methods() {
        return Collections.unmodifiableCollection(methods.values());
    }

    public MethodInfo method(String methodName, String descriptor) {
        return methods.get(methodName + descriptor);
    }

    public boolean isInterface() {
        return (access & Opcodes.ACC_INTERFACE) != 0;
    }

    public boolean isAbstract() {
        return (access & Opcodes.ACC_ABSTRACT) != 0;
    }

    public boolean isFinal() {
        return (access & Opcodes.ACC_FINAL) != 0;
    }

    /** A class that can have instances of its own: not an interface, not abstract. */
    public boolean isConcrete() {
        return !isInterface() && !isAbstract();
    }

    @Override
    public String toString() {
        return name + " (" + scope + ")";
    }
}
