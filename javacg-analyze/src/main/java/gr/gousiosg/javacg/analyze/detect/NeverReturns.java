package gr.gousiosg.javacg.analyze.detect;

import java.util.HashSet;
import java.util.Set;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import gr.gousiosg.javacg.analyze.Model;
import gr.gousiosg.javacg.core.MethodId;
import gr.gousiosg.javacg.stat.ClassInfo;
import gr.gousiosg.javacg.stat.MethodInfo;

/** Methods that cannot return normally, so flow analyses can stop after calling them. */
final class NeverReturns {

    private NeverReturns() {
    }

    /** Application methods with code but no return instruction (they always throw or loop). */
    static Set<MethodId> of(Model model) {
        Set<MethodId> result = new HashSet<>();
        for (ClassInfo c : model.statics().appClasses()) {
            for (MethodInfo m : c.methods()) {
                if (!m.hasCode() || m.isAbstract()) {
                    continue;
                }
                boolean returns = false;
                for (AbstractInsnNode insn : m.node().instructions) {
                    int op = insn.getOpcode();
                    if (op >= Opcodes.IRETURN && op <= Opcodes.RETURN) {
                        returns = true;
                        break;
                    }
                }
                if (!returns) {
                    result.add(m.id());
                }
            }
        }
        return result;
    }

    /** JUnit, TestNG and AssertJ {@code fail(...)} helpers. */
    static boolean isFailHelper(MethodInsnNode mi) {
        if (mi.getOpcode() != Opcodes.INVOKESTATIC || !mi.name.startsWith("fail")) {
            return false;
        }
        return mi.owner.startsWith("org/junit/") || mi.owner.startsWith("org/testng/")
                || mi.owner.startsWith("org/assertj/") || mi.owner.equals("junit/framework/Assert")
                || mi.owner.equals("junit/framework/TestCase");
    }
}
