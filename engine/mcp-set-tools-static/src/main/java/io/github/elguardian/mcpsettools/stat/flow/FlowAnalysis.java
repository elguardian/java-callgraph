package io.github.elguardian.mcpsettools.stat.flow;

import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.Deque;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Interpreter;
import org.objectweb.asm.tree.analysis.Value;

/**
 * Forward data flow over a {@link ControlFlowGraph}, using an ASM {@link Interpreter} for the
 * per-instruction transfer of locals and stack. Unlike ASM's {@code Analyzer} it lets a
 * {@link Hooks} implementation refine the state per edge (e.g. "x is null on the taken branch
 * of {@code ifnull}") and carry extra facts in {@link FlowFrame#facts()}.
 */
public final class FlowAnalysis<V extends Value> {

    /** Analysis-specific transfer refinements. All methods are optional. */
    public interface Hooks<V extends Value> {
        /**
         * Called after the interpreter executed {@code insn}; {@code before} is the input state
         * (do not modify) and {@code after} the output state, which may be adjusted.
         */
        default void afterExecute(int index, AbstractInsnNode insn, FlowFrame<V> before, FlowFrame<V> after) {
        }

        /**
         * Called for each successor of a conditional jump, with a private copy of the output
         * state that may be refined for that edge only.
         */
        default void onBranch(int index, AbstractInsnNode insn, boolean taken, FlowFrame<V> before,
                FlowFrame<V> edge) {
        }

        /**
         * Whether control can continue to the normal successors of {@code insn}; return false
         * after a call known never to return normally.
         */
        default boolean fallsThrough(int index, AbstractInsnNode insn, FlowFrame<V> after) {
            return true;
        }

        /** Called with the state flowing into an exception handler (stack holds the exception). */
        default void onException(int index, AbstractInsnNode insn, ControlFlowGraph.Handler handler,
                FlowFrame<V> before, FlowFrame<V> edge) {
        }
    }

    private final Interpreter<V> interpreter;
    private final Hooks<V> hooks;

    public FlowAnalysis(Interpreter<V> interpreter, Hooks<V> hooks) {
        this.interpreter = interpreter;
        this.hooks = hooks == null ? new Hooks<>() { } : hooks;
    }

    /**
     * Runs to a fixed point. Returns the state <em>before</em> each instruction, or null for
     * unreachable instructions.
     */
    @SuppressWarnings("unchecked")
    public FlowFrame<V>[] analyze(String owner, MethodNode method, ControlFlowGraph cfg) throws AnalyzerException {
        int n = cfg.size();
        FlowFrame<V>[] frames = (FlowFrame<V>[]) new FlowFrame<?>[n];
        if (n == 0) {
            return frames;
        }
        frames[0] = initialFrame(owner, method);
        Deque<Integer> work = new ArrayDeque<>();
        BitSet queued = new BitSet(n);
        work.add(0);
        queued.set(0);
        while (!work.isEmpty()) {
            int i = work.poll();
            queued.clear(i);
            FlowFrame<V> before = frames[i];
            AbstractInsnNode insn = cfg.insn(i);

            for (ControlFlowGraph.Handler h : cfg.handlers(i)) {
                FlowFrame<V> edge = new FlowFrame<>(before);
                edge.clearStack();
                Type catchType = Type.getObjectType(h.block().type == null ? "java/lang/Throwable" : h.block().type);
                edge.push(interpreter.newExceptionValue(h.block(), edge, catchType));
                hooks.onException(i, insn, h, before, edge);
                propagate(frames, h.target(), edge, work, queued);
            }

            FlowFrame<V> after = new FlowFrame<>(before);
            if (insn.getOpcode() >= 0) {
                try {
                    after.execute(insn, interpreter);
                } catch (AnalyzerException e) {
                    throw e;
                } catch (RuntimeException e) {
                    throw new AnalyzerException(insn, "Cannot execute instruction " + i, e);
                }
            }
            hooks.afterExecute(i, insn, before, after);
            if (!hooks.fallsThrough(i, insn, after)) {
                continue;
            }

            int[] succ = cfg.successors(i);
            boolean conditional = succ.length > 1 && insn.getType() == AbstractInsnNode.JUMP_INSN;
            for (int s : succ) {
                FlowFrame<V> out = after;
                if (conditional) {
                    out = new FlowFrame<>(after);
                    hooks.onBranch(i, insn, cfg.isBranchTaken(i, s), before, out);
                }
                propagate(frames, s, out, work, queued);
            }
        }
        return frames;
    }

    private void propagate(FlowFrame<V>[] frames, int target, FlowFrame<V> state, Deque<Integer> work,
            BitSet queued) throws AnalyzerException {
        boolean changed;
        if (frames[target] == null) {
            frames[target] = new FlowFrame<>(state);
            changed = true;
        } else {
            changed = frames[target].mergeAll(state, interpreter);
        }
        if (changed && !queued.get(target)) {
            queued.set(target);
            work.add(target);
        }
    }

    private FlowFrame<V> initialFrame(String owner, MethodNode method) {
        FlowFrame<V> f = new FlowFrame<>(method.maxLocals, method.maxStack);
        boolean isInstance = (method.access & Opcodes.ACC_STATIC) == 0;
        int local = 0;
        if (isInstance) {
            f.setLocal(local, interpreter.newParameterValue(true, local, Type.getObjectType(owner)));
            local++;
        }
        for (Type arg : Type.getArgumentTypes(method.desc)) {
            f.setLocal(local, interpreter.newParameterValue(isInstance, local, arg));
            local++;
            if (arg.getSize() == 2) {
                f.setLocal(local, interpreter.newEmptyValue(local));
                local++;
            }
        }
        while (local < method.maxLocals) {
            f.setLocal(local, interpreter.newEmptyValue(local));
            local++;
        }
        f.setReturn(interpreter.newReturnTypeValue(Type.getReturnType(method.desc)));
        return f;
    }
}
