package gr.gousiosg.javacg.stat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;

import gr.gousiosg.javacg.stat.flow.ControlFlowGraph;
import gr.gousiosg.javacg.stat.flow.FlowAnalysis;
import gr.gousiosg.javacg.stat.flow.FlowFrame;
import gr.gousiosg.javacg.testkit.TestCompiler;

class FlowAnalysisTest {

    private static MethodNode method(String name) {
        byte[] bytes = TestCompiler.create().source("F", """
                public class F {
                    int branch(Object o) {
                        if (o == null) { return 0; }
                        return o.hashCode();
                    }
                    int guarded(String s) {
                        try { return Integer.parseInt(s); }
                        catch (NumberFormatException e) { return -1; }
                    }
                    int dead() { return 1; }
                }
                """).compile().bytes("F");
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, ClassReader.SKIP_FRAMES);
        return cn.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }

    @Test
    void branchHookSeesBothEdgesOfAConditionalJump() throws Exception {
        MethodNode m = method("branch");
        ControlFlowGraph cfg = ControlFlowGraph.of(m);
        List<Boolean> taken = new ArrayList<>();
        FlowFrame<BasicValue>[] frames = new FlowAnalysis<>(new BasicInterpreter(), new FlowAnalysis.Hooks<BasicValue>() {
            @Override
            public void onBranch(int index, AbstractInsnNode insn, boolean isTaken, FlowFrame<BasicValue> before,
                    FlowFrame<BasicValue> edge) {
                assertEquals(Opcodes.IFNONNULL, insn.getOpcode());
                taken.add(isTaken);
                if (isTaken) {
                    edge.facts().set(1);
                }
            }
        }).analyze("F", m, cfg);
        assertEquals(List.of(false, true), taken);
        // the fact set on the taken edge reaches the final IRETURN
        int lastReturn = -1;
        for (int i = 0; i < cfg.size(); i++) {
            if (cfg.insn(i).getOpcode() == Opcodes.IRETURN) {
                lastReturn = i;
            }
        }
        assertTrue(frames[lastReturn].facts().get(1));
        assertEquals(BasicValue.REFERENCE_VALUE, frames[0].getLocal(1));
    }

    @Test
    void exceptionHandlersReceiveTheCaughtType() throws Exception {
        MethodNode m = method("guarded");
        ControlFlowGraph cfg = ControlFlowGraph.of(m);
        FlowFrame<BasicValue>[] frames = new FlowAnalysis<>(new BasicInterpreter(), null).analyze("F", m, cfg);
        int handler = m.instructions.indexOf(m.tryCatchBlocks.get(0).handler);
        assertNotNull(frames[handler]);
        assertEquals(1, frames[handler].getStackSize());
        assertTrue(cfg.predecessors(handler).size() > 0);
    }

    @Test
    void unreachableCodeHasNoFrame() throws Exception {
        MethodNode m = method("dead");
        m.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.NOP));
        m.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.RETURN));
        ControlFlowGraph cfg = ControlFlowGraph.of(m);
        FlowFrame<BasicValue>[] frames = new FlowAnalysis<>(new BasicInterpreter(), null).analyze("F", m, cfg);
        assertNull(frames[cfg.size() - 1]);
    }
}
