package io.github.elguardian.mcpsettools.stat.flow;

import java.util.ArrayList;
import java.util.List;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Interpreter;
import org.objectweb.asm.tree.analysis.Value;

/**
 * Base for interpreters that attach analysis data to ASM's {@link BasicValue}s. The basic value
 * (type category and size) is computed by a {@link BasicInterpreter}; subclasses only decide
 * the extra data through the protected hooks, which by default produce "no information".
 */
public abstract class TrackingInterpreter<V extends TrackingInterpreter.Tracked> extends Interpreter<V> {

    /** A value carrying its {@link BasicValue}; implementations must define equals/hashCode. */
    public interface Tracked extends Value {
        BasicValue basic();

        @Override
        default int getSize() {
            return basic().getSize();
        }
    }

    private final BasicInterpreter basic = new BasicInterpreter();

    protected TrackingInterpreter() {
        super(Opcodes.ASM9);
    }

    /** A value with no information beyond its basic value. */
    protected abstract V plain(BasicValue b);

    /** Joins two values at a control flow merge; return {@code a} itself if nothing changes. */
    protected abstract V join(V a, V b, BasicValue merged);

    protected V parameter(BasicValue b, int local, Type type, boolean isThis) {
        return plain(b);
    }

    protected V caught(BasicValue b, TryCatchBlockNode handler) {
        return plain(b);
    }

    /** Constants, {@code new}, {@code getstatic}, ... ({@link Interpreter#newOperation}). */
    protected V created(AbstractInsnNode insn, BasicValue b) {
        return plain(b);
    }

    /** Loads, stores and stack copies; the default keeps the value as is. */
    protected V copied(AbstractInsnNode insn, V v) {
        return v;
    }

    protected V unary(AbstractInsnNode insn, V v, BasicValue b) {
        return plain(b);
    }

    protected V binary(AbstractInsnNode insn, V a, V c, BasicValue b) {
        return plain(b);
    }

    /** Method calls, {@code invokedynamic} and {@code multianewarray}. */
    protected V nary(AbstractInsnNode insn, List<? extends V> args, BasicValue b) {
        return plain(b);
    }

    @Override
    public V newValue(Type type) {
        BasicValue b = basic.newValue(type);
        return b == null ? null : plain(b);
    }

    @Override
    public V newParameterValue(boolean isInstanceMethod, int local, Type type) {
        return parameter(basic.newValue(type), local, type, isInstanceMethod && local == 0);
    }

    @Override
    public V newEmptyValue(int local) {
        return plain(BasicValue.UNINITIALIZED_VALUE);
    }

    @Override
    public V newExceptionValue(TryCatchBlockNode handler, org.objectweb.asm.tree.analysis.Frame<V> frame,
            Type exceptionType) {
        return caught(basic.newValue(exceptionType), handler);
    }

    @Override
    public V newOperation(AbstractInsnNode insn) throws AnalyzerException {
        return created(insn, basic.newOperation(insn));
    }

    @Override
    public V copyOperation(AbstractInsnNode insn, V value) {
        return copied(insn, value);
    }

    @Override
    public V unaryOperation(AbstractInsnNode insn, V value) throws AnalyzerException {
        BasicValue b = basic.unaryOperation(insn, value.basic());
        return b == null ? null : unary(insn, value, b);
    }

    @Override
    public V binaryOperation(AbstractInsnNode insn, V value1, V value2) throws AnalyzerException {
        BasicValue b = basic.binaryOperation(insn, value1.basic(), value2.basic());
        return b == null ? null : binary(insn, value1, value2, b);
    }

    @Override
    public V ternaryOperation(AbstractInsnNode insn, V value1, V value2, V value3) {
        return null;
    }

    @Override
    public V naryOperation(AbstractInsnNode insn, List<? extends V> values) throws AnalyzerException {
        List<BasicValue> basics = new ArrayList<>(values.size());
        values.forEach(v -> basics.add(v.basic()));
        BasicValue b = basic.naryOperation(insn, basics);
        return b == null ? null : nary(insn, values, b);
    }

    @Override
    public void returnOperation(AbstractInsnNode insn, V value, V expected) {
    }

    @Override
    public V merge(V a, V b) {
        if (a.equals(b)) {
            return a;
        }
        V joined = join(a, b, basic.merge(a.basic(), b.basic()));
        return joined.equals(a) ? a : joined;
    }
}
