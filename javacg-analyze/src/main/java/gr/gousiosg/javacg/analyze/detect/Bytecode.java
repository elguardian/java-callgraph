package gr.gousiosg.javacg.analyze.detect;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import gr.gousiosg.javacg.stat.ClassInfo;
import gr.gousiosg.javacg.stat.MethodInfo;

/** Small bytecode helpers shared by the detectors. */
final class Bytecode {

    private Bytecode() {
    }

    /** Source line of an instruction (the closest preceding line number), or -1. */
    static int line(AbstractInsnNode insn) {
        for (AbstractInsnNode n = insn; n != null; n = n.getPrevious()) {
            if (n instanceof LineNumberNode ln) {
                return ln.line;
            }
        }
        return -1;
    }

    static int line(MethodNode m, int index) {
        return line(m.instructions.get(index));
    }

    /** First source line of a method, or -1. */
    static int firstLine(MethodNode m) {
        for (AbstractInsnNode n : m.instructions) {
            if (n instanceof LineNumberNode ln) {
                return ln.line;
            }
        }
        return -1;
    }

    /**
     * Whether a method's body is worth analyzing: it has code and is not a bridge method or
     * part of a compiler-generated class (lambda bodies are synthetic methods and are kept).
     */
    static boolean analyzable(ClassInfo c, MethodInfo m) {
        return m.hasCode() && !m.isBridge() && !c.isSynthetic();
    }

    /** Stack slots taken by a call's receiver (if any) and arguments. */
    static int operandCount(MethodInsnNode mi) {
        int n = Type.getArgumentTypes(mi.desc).length;
        return mi.getOpcode() == org.objectweb.asm.Opcodes.INVOKESTATIC ? n : n + 1;
    }
}
