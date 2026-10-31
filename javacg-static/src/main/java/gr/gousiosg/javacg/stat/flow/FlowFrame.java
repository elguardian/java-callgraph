package gr.gousiosg.javacg.stat.flow;

import java.util.BitSet;

import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.Interpreter;
import org.objectweb.asm.tree.analysis.Value;

/**
 * An ASM {@link Frame} (locals + operand stack of abstract values) plus a bit set of
 * analysis-specific facts, e.g. "resource allocated at site 3 is still open". Facts merge by
 * union, so they express "on some path".
 */
public final class FlowFrame<V extends Value> extends Frame<V> {

    private final BitSet facts = new BitSet();

    public FlowFrame(int numLocals, int maxStack) {
        super(numLocals, maxStack);
    }

    public FlowFrame(FlowFrame<? extends V> other) {
        super(other);
        facts.or(other.facts);
    }

    public BitSet facts() {
        return facts;
    }

    /** Value {@code depth} slots below the top of the stack (0 = top). */
    public V peek(int depth) {
        return getStack(getStackSize() - 1 - depth);
    }

    boolean mergeAll(FlowFrame<? extends V> other, Interpreter<V> interpreter) throws AnalyzerException {
        boolean changed = merge(other, interpreter);
        BitSet before = (BitSet) facts.clone();
        facts.or(other.facts);
        return changed || !facts.equals(before);
    }
}
