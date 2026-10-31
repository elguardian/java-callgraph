package gr.gousiosg.javacg.stat.flow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;

/**
 * Instruction-level control flow graph of one method. Node {@code i} is
 * {@code method.instructions.get(i)}; labels, line numbers and frames are nodes too (with a
 * single fall-through successor), which keeps indices aligned with the {@code InsnList}.
 */
public final class ControlFlowGraph {

    /** An edge to an exception handler. */
    public record Handler(int target, TryCatchBlockNode block) {
    }

    private final MethodNode method;
    private final int[][] successors;
    private final List<List<Handler>> handlers;
    private List<List<Integer>> predecessors;

    private ControlFlowGraph(MethodNode method, int[][] successors, List<List<Handler>> handlers) {
        this.method = method;
        this.successors = successors;
        this.handlers = handlers;
    }

    /**
     * Builds the graph.
     *
     * @throws UnsupportedOperationException for pre-Java 6 code using {@code jsr}/{@code ret}
     */
    public static ControlFlowGraph of(MethodNode method) {
        InsnList insns = method.instructions;
        int n = insns.size();
        int[][] succ = new int[n][];
        for (int i = 0; i < n; i++) {
            AbstractInsnNode insn = insns.get(i);
            int op = insn.getOpcode();
            if (op == Opcodes.JSR || op == Opcodes.RET) {
                throw new UnsupportedOperationException("jsr/ret subroutines are not supported");
            }
            if (insn instanceof JumpInsnNode j) {
                int target = insns.indexOf(j.label);
                succ[i] = op == Opcodes.GOTO ? new int[] {target} : distinct(i + 1, target);
            } else if (insn instanceof TableSwitchInsnNode t) {
                succ[i] = targets(insns, t.dflt, t.labels);
            } else if (insn instanceof LookupSwitchInsnNode l) {
                succ[i] = targets(insns, l.dflt, l.labels);
            } else if (op >= Opcodes.IRETURN && op <= Opcodes.RETURN || op == Opcodes.ATHROW) {
                succ[i] = new int[0];
            } else {
                succ[i] = i + 1 < n ? new int[] {i + 1} : new int[0];
            }
        }
        List<List<Handler>> handlers = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            handlers.add(new ArrayList<>(0));
        }
        for (TryCatchBlockNode tcb : method.tryCatchBlocks) {
            int start = insns.indexOf(tcb.start);
            int end = insns.indexOf(tcb.end);
            int target = insns.indexOf(tcb.handler);
            for (int i = start; i < end; i++) {
                if (insns.get(i).getOpcode() >= 0) {
                    handlers.get(i).add(new Handler(target, tcb));
                }
            }
        }
        return new ControlFlowGraph(method, succ, handlers);
    }

    public MethodNode method() {
        return method;
    }

    public int size() {
        return successors.length;
    }

    public AbstractInsnNode insn(int i) {
        return method.instructions.get(i);
    }

    /** Normal (non-exceptional) successors. For conditional jumps: fall-through first, then target. */
    public int[] successors(int i) {
        return successors[i];
    }

    /** Exception handlers covering instruction {@code i}, in exception-table order. */
    public List<Handler> handlers(int i) {
        return Collections.unmodifiableList(handlers.get(i));
    }

    /** Normal and exceptional predecessors. */
    public List<Integer> predecessors(int i) {
        if (predecessors == null) {
            List<List<Integer>> preds = new ArrayList<>(size());
            for (int k = 0; k < size(); k++) {
                preds.add(new ArrayList<>(2));
            }
            for (int k = 0; k < size(); k++) {
                for (int s : successors[k]) {
                    preds.get(s).add(k);
                }
                for (Handler h : handlers.get(k)) {
                    if (!preds.get(h.target()).contains(k)) {
                        preds.get(h.target()).add(k);
                    }
                }
            }
            predecessors = preds;
        }
        return Collections.unmodifiableList(predecessors.get(i));
    }

    /** True if instruction {@code i} is a conditional jump ({@code if*}) whose target is {@code to}. */
    public boolean isBranchTaken(int i, int to) {
        AbstractInsnNode insn = insn(i);
        return insn instanceof JumpInsnNode j && insn.getOpcode() != Opcodes.GOTO
                && method.instructions.indexOf(j.label) == to && to != i + 1;
    }

    private static int[] distinct(int a, int b) {
        return a == b ? new int[] {a} : new int[] {a, b};
    }

    private static int[] targets(InsnList insns, LabelNode dflt, List<LabelNode> labels) {
        return Stream.concat(Stream.of(dflt), labels.stream())
                .mapToInt(insns::indexOf).distinct().toArray();
    }
}
