package gr.gousiosg.javacg.analyze.detect;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

import gr.gousiosg.javacg.analyze.Model;
import gr.gousiosg.javacg.core.MethodId;
import gr.gousiosg.javacg.core.trace.TraceSummary;
import gr.gousiosg.javacg.stat.ClassHierarchy;
import gr.gousiosg.javacg.stat.ClassInfo;
import gr.gousiosg.javacg.stat.MethodInfo;
import gr.gousiosg.javacg.stat.flow.ControlFlowGraph;

/**
 * Exception handling:
 * <ul>
 * <li>catch blocks that drop the exception (never read it, never throw);</li>
 * <li>{@code InterruptedException} caught without restoring the interrupt or rethrowing;</li>
 * <li>a new exception thrown from a catch block without the caught one as its cause;</li>
 * <li>at runtime: programming-error exceptions (NPE, ClassCastException, index errors, ...)
 * thrown out of application methods.</li>
 * </ul>
 * A handler's body is the code reachable from it that the rest of the method cannot reach
 * without going through the handler.
 */
final class ExceptionDetector implements Detector {

    static final String SWALLOWED = "exception.swallowed";
    static final String INTERRUPT = "exception.interrupt-ignored";
    static final String CAUSE_LOST = "exception.cause-lost";
    static final String UNEXPECTED = "runtime.unexpected-exception";

    private static final Set<String> BROAD = Set.of("java/lang/Throwable", "java/lang/Exception",
            "java/lang/RuntimeException", "java/lang/Error");
    /** Exceptions that signal a bug rather than an expected condition. */
    private static final Set<String> PROGRAMMING_ERRORS = Set.of("java.lang.NullPointerException",
            "java.lang.ClassCastException", "java.lang.ArrayIndexOutOfBoundsException",
            "java.lang.StringIndexOutOfBoundsException", "java.lang.IndexOutOfBoundsException",
            "java.lang.ArithmeticException", "java.lang.NegativeArraySizeException",
            "java.lang.ArrayStoreException", "java.util.ConcurrentModificationException",
            "java.lang.IllegalMonitorStateException", "java.lang.StackOverflowError",
            "java.lang.OutOfMemoryError", "java.lang.AbstractMethodError", "java.lang.NoSuchMethodError",
            "java.lang.NoClassDefFoundError");

    @Override
    public String id() {
        return "exceptions";
    }

    @Override
    public Map<String, String> rules() {
        return Map.of(SWALLOWED, "A catch block discards the exception",
                INTERRUPT, "InterruptedException is caught and neither rethrown nor the interrupt restored",
                CAUSE_LOST, "A catch block throws a new exception without the caught one as cause",
                UNEXPECTED, "A programming-error exception was thrown out of an application method at runtime");
    }

    @Override
    public List<Finding> run(Model model) {
        List<Finding> findings = new ArrayList<>();
        model.runtime().forEach((m, st) -> st.thrownHere().forEach((type, count) -> {
            if (PROGRAMMING_ERRORS.contains(type)) {
                findings.add(new Finding(UNEXPECTED, Finding.Severity.HIGH, Finding.Confidence.HIGH,
                        type + " thrown out of " + m.name() + "()", m, RecursionDetector.firstLine(model, m),
                        List.of(), List.of(type + " left this method " + count + " time(s), raised in it or in "
                                + "uninstrumented code it called")));
            }
        }));
        if (model.hasStatic()) {
            for (ClassInfo c : model.statics().appClasses()) {
                for (MethodInfo m : c.methods()) {
                    if (Bytecode.analyzable(c, m)) {
                        handlers(model, m, findings);
                    }
                }
            }
        }
        return findings;
    }

    private void handlers(Model model, MethodInfo m, List<Finding> out) {
        MethodNode node = m.node();
        if (node.tryCatchBlocks.isEmpty()) {
            return;
        }
        ControlFlowGraph cfg;
        try {
            cfg = ControlFlowGraph.of(node);
        } catch (UnsupportedOperationException e) {
            return;
        }
        InsnList insns = node.instructions;
        Set<Integer> seenHandlers = new HashSet<>();
        for (TryCatchBlockNode tcb : node.tryCatchBlocks) {
            int handler = insns.indexOf(tcb.handler);
            if (tcb.type == null || !seenHandlers.add(handler)) {
                continue; // finally blocks rethrow; multi-catch shares one handler
            }
            BitSet body = handlerBody(cfg, node, handler);
            AbstractInsnNode first = firstReal(insns, handler);
            int slot = first instanceof VarInsnNode v && first.getOpcode() == Opcodes.ASTORE ? v.var : -1;
            if (slot < 0 && (first == null || first.getOpcode() != Opcodes.POP)) {
                continue; // exception left on the stack and used directly
            }
            boolean used = false;
            boolean throwsSomething = false;
            boolean restoresInterrupt = false;
            for (int i = body.nextSetBit(0); i >= 0; i = body.nextSetBit(i + 1)) {
                AbstractInsnNode insn = insns.get(i);
                if (slot >= 0 && insn instanceof VarInsnNode v && v.var == slot && insn.getOpcode() == Opcodes.ALOAD) {
                    used = true;
                } else if (insn.getOpcode() == Opcodes.ATHROW) {
                    throwsSomething = true;
                } else if (insn instanceof MethodInsnNode mi && mi.owner.equals("java/lang/Thread")
                        && mi.name.equals("interrupt")) {
                    restoresInterrupt = true;
                }
            }
            String name = slot < 0 ? null : NullDereferenceDetector.variableName(node, slot, first.getNext());
            String type = node.tryCatchBlocks.stream().filter(t -> t.handler == tcb.handler && t.type != null)
                    .map(t -> Type.getObjectType(t.type).getClassName()).distinct()
                    .collect(java.util.stream.Collectors.joining(" | "));
            int line = Bytecode.line(firstRealAfterStore(insns, handler));
            List<String> evidence = caughtAtRuntime(model, m.id(), tcb.type);
            if (tcb.type.equals("java/lang/InterruptedException")) {
                if (!throwsSomething && !restoresInterrupt) {
                    out.add(withRuntime(new Finding(INTERRUPT, Finding.Severity.MEDIUM, Finding.Confidence.HIGH,
                            "InterruptedException caught without Thread.currentThread().interrupt() or rethrow; "
                                    + "the thread's interrupt request is lost",
                            m.id(), line, List.of(), List.of()), evidence));
                }
            } else if (!used && !throwsSomething) {
                if (name != null && (name.startsWith("ignore") || name.equals("expected") || name.equals("_"))) {
                    continue; // explicitly marked as intentional
                }
                boolean broad = BROAD.contains(tcb.type);
                out.add(withRuntime(new Finding(SWALLOWED, broad ? Finding.Severity.MEDIUM : Finding.Severity.LOW,
                        Finding.Confidence.MEDIUM,
                        "Caught " + type + " is discarded" + (broad ? "; a broad catch hides unrelated failures" : ""),
                        m.id(), line, List.of(), List.of()), evidence));
            } else if (!used && throwsSomething && slot >= 0) {
                out.add(withRuntime(new Finding(CAUSE_LOST, Finding.Severity.LOW, Finding.Confidence.MEDIUM,
                        "A new exception is thrown while handling " + type + " without passing it as the cause",
                        m.id(), line, List.of(), List.of()), evidence));
            }
        }
    }

    private static Finding withRuntime(Finding f, List<String> evidence) {
        return evidence.isEmpty() ? f : f.withEvidence(evidence, Finding.Confidence.HIGH);
    }

    /** Exceptions the method caught at runtime that this handler's type covers. */
    private static List<String> caughtAtRuntime(Model model, MethodId m, String handlerType) {
        TraceSummary.MethodStats st = model.runtime(m);
        if (st == null) {
            return List.of();
        }
        ClassHierarchy h = model.statics().hierarchy();
        List<String> evidence = new ArrayList<>();
        st.caught().forEach((type, count) -> {
            if (h.isSubtypeOf(type.replace('.', '/'), handlerType)) {
                evidence.add(type + " from a callee was caught by this method " + count + " time(s)");
            }
        });
        return evidence;
    }

    /** Instructions reachable from the handler that are unreachable from entry without it. */
    private static BitSet handlerBody(ControlFlowGraph cfg, MethodNode node, int handler) {
        BitSet withoutHandler = reach(cfg, 0, handler);
        BitSet fromHandler = reach(cfg, handler, -1);
        fromHandler.andNot(withoutHandler);
        return fromHandler;
    }

    private static BitSet reach(ControlFlowGraph cfg, int start, int blockedHandler) {
        BitSet seen = new BitSet(cfg.size());
        Deque<Integer> work = new ArrayDeque<>(List.of(start));
        seen.set(start);
        while (!work.isEmpty()) {
            int i = work.pop();
            for (int s : cfg.successors(i)) {
                if (!seen.get(s)) {
                    seen.set(s);
                    work.push(s);
                }
            }
            for (ControlFlowGraph.Handler h : cfg.handlers(i)) {
                if (h.target() != blockedHandler && !seen.get(h.target())) {
                    seen.set(h.target());
                    work.push(h.target());
                }
            }
        }
        return seen;
    }

    private static AbstractInsnNode firstReal(InsnList insns, int from) {
        for (AbstractInsnNode n = insns.get(from); n != null; n = n.getNext()) {
            if (n.getOpcode() >= 0) {
                return n;
            }
        }
        return null;
    }

    /** The instruction after the handler's store/pop: where the catch body's source line starts. */
    private static AbstractInsnNode firstRealAfterStore(InsnList insns, int from) {
        AbstractInsnNode first = firstReal(insns, from);
        if (first == null) {
            return insns.get(from);
        }
        for (AbstractInsnNode n = first.getNext(); n != null; n = n.getNext()) {
            if (n.getOpcode() >= 0) {
                return n;
            }
        }
        return first;
    }
}
