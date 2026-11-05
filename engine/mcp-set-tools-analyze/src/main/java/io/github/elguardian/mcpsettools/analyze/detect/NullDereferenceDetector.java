package io.github.elguardian.mcpsettools.analyze.detect;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicValue;

import io.github.elguardian.mcpsettools.analyze.Model;
import io.github.elguardian.mcpsettools.core.MethodId;
import io.github.elguardian.mcpsettools.core.trace.TraceSummary;
import io.github.elguardian.mcpsettools.stat.ClassInfo;
import io.github.elguardian.mcpsettools.stat.MethodInfo;
import io.github.elguardian.mcpsettools.stat.flow.ControlFlowGraph;
import io.github.elguardian.mcpsettools.stat.flow.FlowAnalysis;
import io.github.elguardian.mcpsettools.stat.flow.FlowFrame;
import io.github.elguardian.mcpsettools.stat.flow.TrackingInterpreter;

/**
 * Intraprocedural null analysis. Tracks, per local and stack slot, whether a reference is
 * null, non-null, null on some path, or unknown; branches on {@code ifnull}/{@code ifnonnull}
 * refine the tested local, and a dereference makes it non-null afterwards. Values from
 * parameters, fields and method results are unknown, so only nulls the method itself
 * produces (a {@code null} constant, or a comparison with null) are reported.
 */
final class NullDereferenceDetector implements Detector {

    static final String DEFINITE = "null.dereference";
    static final String CHECKED = "null.dereference-after-check";
    static final String POSSIBLE = "null.dereference-possible";
    private static final String NPE = "java.lang.NullPointerException";

    @Override
    public String id() {
        return "nulls";
    }

    @Override
    public Map<String, String> rules() {
        return Map.of(DEFINITE, "A value that is always null is dereferenced",
                CHECKED, "A value compared with null is dereferenced on a path where it is null",
                POSSIBLE, "A null constant reaches a dereference on some path");
    }

    enum Nullness {
        NULL, NONNULL, MAYBE, UNKNOWN;

        Nullness join(Nullness o) {
            if (this == o) {
                return this;
            }
            if (this == NULL || o == NULL || this == MAYBE || o == MAYBE) {
                return MAYBE;
            }
            return UNKNOWN;
        }
    }

    /** Where a null came from: a constant, or the null edge of a comparison. */
    enum Origin {
        NONE, CONSTANT, CHECK
    }

    record NullValue(BasicValue basic, Nullness nullness, Origin origin, int originLine, int local)
            implements TrackingInterpreter.Tracked {

        NullValue with(Nullness n, Origin o, int line) {
            return new NullValue(basic, n, o, line, local);
        }

        NullValue fromLocal(int k) {
            return new NullValue(basic, nullness, origin, originLine, k);
        }

        boolean isReference() {
            return basic.equals(BasicValue.REFERENCE_VALUE);
        }
    }

    private static final class Interp extends TrackingInterpreter<NullValue> {
        @Override
        protected NullValue plain(BasicValue b) {
            return new NullValue(b, Nullness.UNKNOWN, Origin.NONE, -1, -1);
        }

        @Override
        protected NullValue parameter(BasicValue b, int local, Type type, boolean isThis) {
            return new NullValue(b, isThis ? Nullness.NONNULL : Nullness.UNKNOWN, Origin.NONE, -1, -1);
        }

        @Override
        protected NullValue caught(BasicValue b, TryCatchBlockNode handler) {
            return new NullValue(b, Nullness.NONNULL, Origin.NONE, -1, -1);
        }

        @Override
        protected NullValue created(AbstractInsnNode insn, BasicValue b) {
            return switch (insn.getOpcode()) {
                case Opcodes.ACONST_NULL -> new NullValue(b, Nullness.NULL, Origin.CONSTANT, Bytecode.line(insn), -1);
                case Opcodes.NEW, Opcodes.LDC -> new NullValue(b, Nullness.NONNULL, Origin.NONE, -1, -1);
                default -> plain(b);
            };
        }

        @Override
        protected NullValue copied(AbstractInsnNode insn, NullValue v) {
            if (insn.getOpcode() == Opcodes.ALOAD) {
                return v.fromLocal(((VarInsnNode) insn).var);
            }
            return insn.getOpcode() == Opcodes.ASTORE ? v.fromLocal(-1) : v;
        }

        @Override
        protected NullValue unary(AbstractInsnNode insn, NullValue v, BasicValue b) {
            return switch (insn.getOpcode()) {
                case Opcodes.CHECKCAST -> v;
                case Opcodes.NEWARRAY, Opcodes.ANEWARRAY -> new NullValue(b, Nullness.NONNULL, Origin.NONE, -1, -1);
                default -> plain(b);
            };
        }

        @Override
        protected NullValue nary(AbstractInsnNode insn, List<? extends NullValue> args, BasicValue b) {
            int op = insn.getOpcode();
            if (op == Opcodes.MULTIANEWARRAY || op == Opcodes.INVOKEDYNAMIC) {
                return new NullValue(b, Nullness.NONNULL, Origin.NONE, -1, -1);
            }
            return plain(b);
        }

        @Override
        protected NullValue join(NullValue a, NullValue b, BasicValue merged) {
            if (!merged.equals(BasicValue.REFERENCE_VALUE)) {
                return plain(merged);
            }
            Nullness n = a.nullness.join(b.nullness);
            // keep the origin of the side that may be null
            NullValue nullSide = a.nullness == Nullness.NULL || a.nullness == Nullness.MAYBE ? a : b;
            Origin origin = n == Nullness.NULL || n == Nullness.MAYBE ? nullSide.origin : Origin.NONE;
            if (a.origin == Origin.CHECK || b.origin == Origin.CHECK) {
                origin = n == Nullness.MAYBE || n == Nullness.NULL ? Origin.CHECK : origin;
            }
            int line = origin == Origin.NONE ? -1
                    : a.origin == origin && a.originLine > 0 ? a.originLine : b.originLine;
            int local = a.local == b.local ? a.local : -1;
            return new NullValue(merged, n, origin, line, local);
        }
    }

    @Override
    public List<Finding> run(Model model) {
        if (!model.hasStatic()) {
            return List.of();
        }
        Set<MethodId> neverReturn = NeverReturns.of(model);
        List<Finding> findings = new ArrayList<>();
        for (ClassInfo c : model.statics().appClasses()) {
            for (MethodInfo m : c.methods()) {
                if (Bytecode.analyzable(c, m)) {
                    analyze(model, c, m, neverReturn, findings);
                }
            }
        }
        return findings;
    }

    private void analyze(Model model, ClassInfo c, MethodInfo m, Set<MethodId> neverReturn, List<Finding> out) {
        MethodNode node = m.node();
        InsnList insns = node.instructions;
        Set<Integer> reportedLines = new HashSet<>();
        List<Finding> local = new ArrayList<>();
        FlowAnalysis.Hooks<NullValue> hooks = new FlowAnalysis.Hooks<>() {
            @Override
            public void afterExecute(int index, AbstractInsnNode insn, FlowFrame<NullValue> before,
                    FlowFrame<NullValue> after) {
                int depth = dereferenceDepth(insn);
                if (depth < 0 || before.getStackSize() <= depth) {
                    return;
                }
                NullValue v = before.peek(depth);
                if (!v.isReference()) {
                    return;
                }
                if (v.local >= 0 && v.local < after.getLocals()) {
                    NullValue cur = after.getLocal(v.local);
                    if (cur.isReference() && cur.nullness != Nullness.NONNULL) {
                        after.setLocal(v.local, cur.with(Nullness.NONNULL, Origin.NONE, -1));
                    }
                }
            }

            @Override
            public void onBranch(int index, AbstractInsnNode insn, boolean taken, FlowFrame<NullValue> before,
                    FlowFrame<NullValue> edge) {
                int op = insn.getOpcode();
                if (op != Opcodes.IFNULL && op != Opcodes.IFNONNULL) {
                    return;
                }
                NullValue tested = before.peek(0);
                if (tested.local < 0 || tested.local >= edge.getLocals()) {
                    return;
                }
                NullValue cur = edge.getLocal(tested.local);
                if (!cur.isReference()) {
                    return;
                }
                boolean isNull = op == Opcodes.IFNULL == taken;
                edge.setLocal(tested.local, isNull ? cur.with(Nullness.NULL, Origin.CHECK, Bytecode.line(insn))
                        : cur.with(Nullness.NONNULL, Origin.NONE, -1));
            }

            @Override
            public boolean fallsThrough(int index, AbstractInsnNode insn, FlowFrame<NullValue> after) {
                return !(insn instanceof MethodInsnNode mi && neverReturn.contains(MethodId.of(mi.owner, mi.name, mi.desc))
                        || insn instanceof MethodInsnNode mi2 && NeverReturns.isFailHelper(mi2));
            }
        };
        FlowFrame<NullValue>[] frames;
        try {
            frames = new FlowAnalysis<>(new Interp(), hooks).analyze(c.name(), node, ControlFlowGraph.of(node));
        } catch (AnalyzerException | UnsupportedOperationException e) {
            return;
        }
        // report from the fixed point only: a value seen as null on a first visit may merge later
        for (int i = 0; i < frames.length; i++) {
            AbstractInsnNode insn = insns.get(i);
            int depth = dereferenceDepth(insn);
            if (frames[i] == null || depth < 0 || frames[i].getStackSize() <= depth) {
                continue;
            }
            NullValue v = frames[i].peek(depth);
            if (v.isReference() && (v.nullness == Nullness.NULL || v.nullness == Nullness.MAYBE)) {
                int line = Bytecode.line(insn);
                if (reportedLines.add(line)) {
                    local.add(finding(m, node, insn, v, line));
                }
            }
        }
        TraceSummary.MethodStats rt = model.runtime(m.id());
        Long npes = rt == null ? null : rt.thrownHere().get(NPE);
        for (Finding f : local) {
            out.add(npes == null ? f
                    : f.withEvidence(List.of(NPE + " thrown by this method " + npes + " time(s)"),
                            Finding.Confidence.HIGH));
        }
    }

    private static Finding finding(MethodInfo m, MethodNode node, AbstractInsnNode insn, NullValue v, int line) {
        String name = v.local >= 0 ? variableName(node, v.local, insn) : null;
        String what = name != null ? "'" + name + "'" : "a value";
        List<Finding.Location> related = v.originLine > 0
                ? List.of(new Finding.Location(m.id(), v.originLine,
                        v.origin == Origin.CHECK ? "compared with null" : "assigned null"))
                : List.of();
        if (v.nullness == Nullness.NULL) {
            return new Finding(DEFINITE, Finding.Severity.HIGH, Finding.Confidence.HIGH,
                    what + " is always null here and is dereferenced", m.id(), line, related, List.of());
        }
        if (v.origin == Origin.CHECK) {
            return new Finding(CHECKED, Finding.Severity.HIGH, Finding.Confidence.MEDIUM,
                    what + " is compared with null but dereferenced on a path where it is null", m.id(), line,
                    related, List.of());
        }
        return new Finding(POSSIBLE, Finding.Severity.MEDIUM, Finding.Confidence.LOW,
                what + " may be null here (assigned null on some path) and is dereferenced", m.id(), line, related,
                List.of());
    }

    /** Stack depth of the dereferenced reference for {@code insn}, or -1 if it dereferences nothing. */
    private static int dereferenceDepth(AbstractInsnNode insn) {
        return switch (insn.getOpcode()) {
            case Opcodes.GETFIELD, Opcodes.ARRAYLENGTH, Opcodes.ATHROW, Opcodes.MONITORENTER -> 0;
            case Opcodes.PUTFIELD, Opcodes.IALOAD, Opcodes.LALOAD, Opcodes.FALOAD, Opcodes.DALOAD, Opcodes.AALOAD,
                    Opcodes.BALOAD, Opcodes.CALOAD, Opcodes.SALOAD -> 1;
            case Opcodes.IASTORE, Opcodes.LASTORE, Opcodes.FASTORE, Opcodes.DASTORE, Opcodes.AASTORE,
                    Opcodes.BASTORE, Opcodes.CASTORE, Opcodes.SASTORE -> 2;
            case Opcodes.INVOKEVIRTUAL, Opcodes.INVOKEINTERFACE -> Type.getArgumentTypes(((MethodInsnNode) insn).desc).length;
            case Opcodes.INVOKESPECIAL -> ((MethodInsnNode) insn).name.equals("<init>") ? -1
                    : Type.getArgumentTypes(((MethodInsnNode) insn).desc).length;
            default -> -1;
        };
    }

    static String variableName(MethodNode node, int slot, AbstractInsnNode at) {
        if (node.localVariables == null) {
            return null;
        }
        int index = node.instructions.indexOf(at);
        for (LocalVariableNode lv : node.localVariables) {
            if (lv.index == slot && node.instructions.indexOf(lv.start) <= index
                    && index <= node.instructions.indexOf(lv.end)) {
                return lv.name;
            }
        }
        return null;
    }
}
