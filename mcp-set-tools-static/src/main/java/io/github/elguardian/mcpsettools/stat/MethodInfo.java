package io.github.elguardian.mcpsettools.stat;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.MethodNode;

import io.github.elguardian.mcpsettools.core.MethodId;

/** A method declared by a {@link ClassInfo}. {@link #node()} is non-null only for application code. */
public final class MethodInfo {

    private final ClassInfo owner;
    private final MethodId id;
    private final int access;
    private final MethodNode node;

    MethodInfo(ClassInfo owner, String name, String descriptor, int access, MethodNode node) {
        this.owner = owner;
        this.id = MethodId.of(owner.name(), name, descriptor);
        this.access = access;
        this.node = node;
    }

    public ClassInfo owner() {
        return owner;
    }

    public MethodId id() {
        return id;
    }

    public int access() {
        return access;
    }

    public MethodNode node() {
        return node;
    }

    /** True when there is bytecode to analyze. */
    public boolean hasCode() {
        return node != null && node.instructions.size() > 0;
    }

    public boolean isStatic() {
        return (access & Opcodes.ACC_STATIC) != 0;
    }

    public boolean isAbstract() {
        return (access & Opcodes.ACC_ABSTRACT) != 0;
    }

    public boolean isPrivate() {
        return (access & Opcodes.ACC_PRIVATE) != 0;
    }

    public boolean isFinal() {
        return (access & Opcodes.ACC_FINAL) != 0;
    }

    public boolean isNative() {
        return (access & Opcodes.ACC_NATIVE) != 0;
    }

    public boolean isSynchronized() {
        return (access & Opcodes.ACC_SYNCHRONIZED) != 0;
    }

    public boolean isBridge() {
        return (access & Opcodes.ACC_BRIDGE) != 0;
    }

    public boolean isSynthetic() {
        return (access & Opcodes.ACC_SYNTHETIC) != 0;
    }

    @Override
    public String toString() {
        return id.toString();
    }
}
