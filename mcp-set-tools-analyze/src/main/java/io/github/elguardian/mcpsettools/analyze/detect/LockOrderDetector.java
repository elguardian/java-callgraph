package io.github.elguardian.mcpsettools.analyze.detect;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicValue;

import io.github.elguardian.mcpsettools.analyze.Model;
import io.github.elguardian.mcpsettools.core.MethodId;
import io.github.elguardian.mcpsettools.core.Provenance;
import io.github.elguardian.mcpsettools.core.trace.CallStacks;
import io.github.elguardian.mcpsettools.core.trace.Recording;
import io.github.elguardian.mcpsettools.core.trace.TraceSummary;
import io.github.elguardian.mcpsettools.stat.CallSite;
import io.github.elguardian.mcpsettools.stat.ClassInfo;
import io.github.elguardian.mcpsettools.stat.MethodInfo;
import io.github.elguardian.mcpsettools.stat.flow.ControlFlowGraph;
import io.github.elguardian.mcpsettools.stat.flow.FlowAnalysis;
import io.github.elguardian.mcpsettools.stat.flow.FlowFrame;
import io.github.elguardian.mcpsettools.stat.flow.TrackingInterpreter;

/**
 * Lock-order inversions: two locks acquired in opposite orders (more generally, a cycle in the
 * "acquired B while holding A" graph) can deadlock.
 *
 * <ul>
 * <li><b>Runtime</b> (needs {@code locks=true} recordings): lock objects are real instances
 * (identity hash + class); a cycle means two code paths that ran took the same two locks in
 * opposite orders.</li>
 * <li><b>Static</b>: locks are abstracted to "instance of C", a field or a class literal.
 * Each method's acquisitions ({@code synchronized} methods and blocks, {@code Lock.lock()})
 * propagate to its callers through the call graph, so orders on paths that never ran are
 * found too, at type-level precision.</li>
 * <li>Nesting two locks of the same type taken from parameters ({@code synchronized (from)
 * { synchronized (to) ...}}) deadlocks when two callers pass them in opposite order.</li>
 * </ul>
 */
final class LockOrderDetector implements Detector {

    static final String RUNTIME = "deadlock.lock-order";
    static final String STATIC = "deadlock.static-lock-order";
    static final String SAME_TYPE = "deadlock.nested-same-type";

    private static final Set<String> LOCK_TYPES = Set.of("java/util/concurrent/locks/Lock",
            "java/util/concurrent/locks/ReentrantLock", "java/util/concurrent/locks/ReentrantReadWriteLock$ReadLock",
            "java/util/concurrent/locks/ReentrantReadWriteLock$WriteLock");

    @Override
    public String id() {
        return "locks";
    }

    @Override
    public Map<String, String> rules() {
        return Map.of(RUNTIME, "Locks acquired in inconsistent order at runtime (potential deadlock)",
                STATIC, "Lock types acquired in inconsistent order along static call paths",
                SAME_TYPE, "Two locks of the same type nested in an order chosen by the caller");
    }

    @Override
    public List<Finding> run(Model model) {
        List<Finding> findings = new ArrayList<>();
        for (TraceSummary t : model.traces()) {
            findings.addAll(runtime(t.recording()));
        }
        if (model.hasStatic()) {
            findings.addAll(statics(model));
        }
        return findings;
    }

    // ---------------------------------------------------------------- runtime

    /** "Acquired {@code to} while holding {@code from}", first occurrence. */
    record Witness(String thread, MethodId acquiredIn, MethodId heldSince) {
    }

    private List<Finding> runtime(Recording rec) {
        Map<Long, Map<Long, Witness>> order = new LinkedHashMap<>();
        Map<Long, String> lockNames = new HashMap<>();
        Map<Long, LinkedHashMap<Long, int[]>> held = new HashMap<>();
        Map<Long, Map<Long, MethodId>> heldSince = new HashMap<>();
        try {
            rec.replay(new CallStacks(new CallStacks.Listener() {
                @Override
                public void onLock(long thread, List<CallStacks.Frame> stack, int hash, int cls, long nanos,
                        boolean acquire) {
                    long key = (long) hash << 32 | cls & 0xFFFFFFFFL;
                    LinkedHashMap<Long, int[]> mine = held.computeIfAbsent(thread, k -> new LinkedHashMap<>());
                    Map<Long, MethodId> since = heldSince.computeIfAbsent(thread, k -> new HashMap<>());
                    MethodId where = stack.isEmpty() ? null : rec.method(stack.get(stack.size() - 1).method());
                    if (acquire) {
                        lockNames.putIfAbsent(key, rec.className(cls) + "@" + Integer.toHexString(hash));
                        int[] count = mine.get(key);
                        if (count != null) {
                            count[0]++;
                            return;
                        }
                        for (long h : mine.keySet()) {
                            order.computeIfAbsent(h, k -> new LinkedHashMap<>()).putIfAbsent(key,
                                    new Witness(rec.threadName(thread), where, since.get(h)));
                        }
                        mine.put(key, new int[] {1});
                        since.put(key, where);
                    } else {
                        int[] count = mine.get(key);
                        if (count != null && --count[0] == 0) {
                            mine.remove(key);
                            since.remove(key);
                        }
                    }
                }
            }));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<Finding> findings = new ArrayList<>();
        for (List<Long> cycle : Cycles.find(order.keySet(), k -> order.getOrDefault(k, Map.of()).keySet())) {
            List<String> evidence = new ArrayList<>();
            List<Finding.Location> related = new ArrayList<>();
            Set<String> threads = new LinkedHashSet<>();
            MethodId first = null;
            for (int i = 0; i < cycle.size(); i++) {
                long from = cycle.get(i);
                long to = cycle.get((i + 1) % cycle.size());
                Witness w = order.get(from).get(to);
                threads.add(w.thread());
                first = first == null ? w.acquiredIn() : first;
                evidence.add("thread '" + w.thread() + "' acquired " + lockNames.get(to) + " in "
                        + name(w.acquiredIn()) + " while holding " + lockNames.get(from) + " (taken in "
                        + name(w.heldSince()) + ")");
                if (w.acquiredIn() != null) {
                    related.add(new Finding.Location(w.acquiredIn(), -1, "acquires " + lockNames.get(to)));
                }
            }
            String locks = String.join(" -> ", cycle.stream().map(lockNames::get).toList());
            findings.add(new Finding(RUNTIME, Finding.Severity.HIGH,
                    threads.size() > 1 ? Finding.Confidence.HIGH : Finding.Confidence.MEDIUM,
                    "Locks taken in inconsistent order (" + locks + " -> ...): threads running these paths "
                            + "concurrently can deadlock" + (threads.size() > 1 ? "" : "; seen on one thread only"),
                    first, -1, related, evidence));
        }
        return findings;
    }

    private static String name(MethodId m) {
        return m == null ? "?" : m.javaName();
    }

    // ---------------------------------------------------------------- static

    /**
     * A lock expression's abstract identity ({@code lock}, null when unknown). Values read from a
     * reference parameter also remember the parameter slot, to tell apart two parameters of
     * the same type.
     */
    record LockValue(BasicValue basic, String lock, String paramType, int paramSlot)
            implements TrackingInterpreter.Tracked {

        String paramLabel() {
            return paramType == null ? null : "param#" + paramType + "#" + paramSlot;
        }
    }

    private static final class Interp extends TrackingInterpreter<LockValue> {
        private final String owner;

        Interp(String owner) {
            this.owner = owner;
        }

        @Override
        protected LockValue plain(BasicValue b) {
            return new LockValue(b, null, null, -1);
        }

        @Override
        protected LockValue parameter(BasicValue b, int local, Type type, boolean isThis) {
            if (isThis) {
                return new LockValue(b, "instance of " + Type.getObjectType(owner).getClassName(), null, -1);
            }
            if (type.getSort() == Type.OBJECT) {
                return new LockValue(b, "instance of " + type.getClassName(), type.getInternalName(), local);
            }
            return plain(b);
        }

        @Override
        protected LockValue created(AbstractInsnNode insn, BasicValue b) {
            if (insn instanceof FieldInsnNode f && insn.getOpcode() == Opcodes.GETSTATIC) {
                return new LockValue(b, "static field " + Type.getObjectType(f.owner).getClassName() + "." + f.name,
                        null, -1);
            }
            if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Type t && t.getSort() == Type.OBJECT) {
                return new LockValue(b, "class " + t.getClassName(), null, -1);
            }
            return plain(b);
        }

        @Override
        protected LockValue unary(AbstractInsnNode insn, LockValue v, BasicValue b) {
            if (insn instanceof FieldInsnNode f && insn.getOpcode() == Opcodes.GETFIELD) {
                return new LockValue(b, "field " + Type.getObjectType(f.owner).getClassName() + "." + f.name, null,
                        -1);
            }
            return insn.getOpcode() == Opcodes.CHECKCAST ? v : plain(b);
        }

        @Override
        protected LockValue join(LockValue a, LockValue b, BasicValue merged) {
            boolean sameLock = a.lock != null && a.lock.equals(b.lock);
            boolean sameParam = sameLock && a.paramSlot == b.paramSlot && a.paramType != null;
            return new LockValue(merged, sameLock ? a.lock : null, sameParam ? a.paramType : null,
                    sameParam ? a.paramSlot : -1);
        }
    }

    /** One method's lock behaviour. */
    private static final class Summary {
        final Set<String> acquires = new LinkedHashSet<>();
        /** Directly nested acquisitions: held, acquired, line. */
        final List<Nesting> nested = new ArrayList<>();
        /** Calls made while holding locks. */
        final List<HeldCall> heldCalls = new ArrayList<>();
    }

    private record Nesting(String held, String acquired, int line) {
    }

    private record HeldCall(Set<String> held, int index, int line) {
    }

    /** Static lock-order edge evidence. */
    private record StaticWitness(MethodId method, int line, String how) {
    }

    private List<Finding> statics(Model model) {
        Map<MethodId, Map<Integer, CallSite>> sites = new HashMap<>();
        for (CallSite cs : model.statics().callSites()) {
            sites.computeIfAbsent(cs.caller(), k -> new HashMap<>()).put(cs.index(), cs);
        }
        Map<MethodId, Summary> summaries = new HashMap<>();
        List<Finding> findings = new ArrayList<>();
        for (ClassInfo c : model.statics().appClasses()) {
            for (MethodInfo m : c.methods()) {
                if (Bytecode.analyzable(c, m)) {
                    Summary s = summarize(c, m, findings);
                    if (s != null) {
                        summaries.put(m.id(), s);
                    }
                }
            }
        }
        // locks a call to m may take, transitively, over static edges
        Map<MethodId, Set<String>> transitive = new HashMap<>();
        summaries.forEach((m, s) -> transitive.put(m, new LinkedHashSet<>(s.acquires)));
        boolean changed = true;
        while (changed) {
            changed = false;
            for (MethodId m : summaries.keySet()) {
                Set<String> mine = transitive.get(m);
                for (var e : model.graph().outgoing(m)) {
                    Set<String> theirs = e.provenance() == Provenance.DYNAMIC ? null : transitive.get(e.callee());
                    if (theirs != null && mine.addAll(theirs)) {
                        changed = true;
                    }
                }
            }
        }
        Map<String, Map<String, StaticWitness>> order = new LinkedHashMap<>();
        summaries.forEach((m, s) -> {
            for (Nesting n : s.nested) {
                order.computeIfAbsent(n.held(), k -> new LinkedHashMap<>()).putIfAbsent(n.acquired(),
                        new StaticWitness(m, n.line(), "nested acquisition"));
            }
            for (HeldCall hc : s.heldCalls) {
                CallSite cs = sites.getOrDefault(m, Map.of()).get(hc.index());
                if (cs == null) {
                    continue;
                }
                for (MethodId target : cs.targets()) {
                    for (String acquired : transitive.getOrDefault(target, Set.of())) {
                        for (String h : hc.held()) {
                            if (!h.equals(acquired)) {
                                order.computeIfAbsent(h, k -> new LinkedHashMap<>()).putIfAbsent(acquired,
                                        new StaticWitness(m, hc.line(), "calls " + target.javaName()));
                            }
                        }
                    }
                }
            }
        });
        for (List<String> cycle : Cycles.find(order.keySet(), k -> order.getOrDefault(k, Map.of()).keySet())) {
            List<Finding.Location> related = new ArrayList<>();
            for (int i = 0; i < cycle.size(); i++) {
                String from = cycle.get(i);
                String to = cycle.get((i + 1) % cycle.size());
                StaticWitness w = order.get(from).get(to);
                related.add(new Finding.Location(w.method(), w.line(),
                        "takes " + to + " while holding " + from + " (" + w.how() + ")"));
            }
            StaticWitness first = order.get(cycle.get(0)).get(cycle.get(1 % cycle.size()));
            findings.add(new Finding(STATIC, Finding.Severity.MEDIUM, Finding.Confidence.LOW,
                    "Lock order cycle " + String.join(" -> ", cycle) + " -> " + cycle.get(0)
                            + " along static call paths (type-level: the instances may differ)",
                    first.method(), first.line(), related, List.of()));
        }
        return findings;
    }

    private Summary summarize(ClassInfo c, MethodInfo m, List<Finding> findings) {
        MethodNode node = m.node();
        Summary s = new Summary();
        List<String> labels = new ArrayList<>();
        String className = Type.getObjectType(c.name()).getClassName();
        String methodLock = !m.isSynchronized() ? null
                : m.isStatic() ? "class " + className : "instance of " + className;
        if (methodLock != null) {
            s.acquires.add(methodLock);
        }
        // transfer: the facts are the labels (locks and parameter identities) currently held
        FlowAnalysis.Hooks<LockValue> hooks = new FlowAnalysis.Hooks<>() {
            @Override
            public void afterExecute(int index, AbstractInsnNode insn, FlowFrame<LockValue> before,
                    FlowFrame<LockValue> after) {
                LockValue v = lockOperand(insn, before);
                if (v == null || v.lock() == null) {
                    return;
                }
                if (isAcquire(insn)) {
                    after.facts().set(index(labels, v.lock()));
                    if (v.paramLabel() != null) {
                        after.facts().set(index(labels, v.paramLabel()));
                    }
                } else {
                    after.facts().clear(index(labels, v.lock()));
                    if (v.paramLabel() != null) {
                        after.facts().clear(index(labels, v.paramLabel()));
                    }
                }
            }
        };
        FlowFrame<LockValue>[] frames;
        try {
            frames = new FlowAnalysis<>(new Interp(c.name()), hooks).analyze(c.name(), node, ControlFlowGraph.of(node));
        } catch (AnalyzerException | UnsupportedOperationException e) {
            return null;
        }
        // read the summary off the fixed point
        boolean sameTypeReported = false;
        for (int i = 0; i < frames.length; i++) {
            FlowFrame<LockValue> before = frames[i];
            if (before == null) {
                continue;
            }
            AbstractInsnNode insn = node.instructions.get(i);
            int line = Bytecode.line(insn);
            Set<String> held = new LinkedHashSet<>();
            if (methodLock != null) {
                held.add(methodLock);
            }
            before.facts().stream().mapToObj(labels::get).filter(l -> !l.startsWith("param#")).forEach(held::add);
            LockValue v = lockOperand(insn, before);
            if (v != null && isAcquire(insn)) {
                if (v.lock() == null) {
                    continue;
                }
                s.acquires.add(v.lock());
                for (String h : held) {
                    if (!h.equals(v.lock())) {
                        s.nested.add(new Nesting(h, v.lock(), line));
                    }
                }
                if (v.paramLabel() != null && !sameTypeReported) {
                    String prefix = "param#" + v.paramType() + "#";
                    String own = v.paramLabel();
                    if (before.facts().stream().mapToObj(labels::get).anyMatch(l -> l.startsWith(prefix) && !l.equals(own))) {
                        sameTypeReported = true;
                        findings.add(new Finding(SAME_TYPE, Finding.Severity.MEDIUM, Finding.Confidence.MEDIUM,
                                "Two " + Type.getObjectType(v.paramType()).getClassName()
                                        + " parameters are locked one inside the other; callers passing them in "
                                        + "opposite order deadlock (impose a global order, e.g. by id)",
                                m.id(), line, List.of(), List.of()));
                    }
                }
            } else if (v == null && insn instanceof MethodInsnNode && !held.isEmpty()) {
                s.heldCalls.add(new HeldCall(held, i, line));
            }
        }
        return s;
    }

    /** The lock operand of a monitor instruction or Lock call, or null for other instructions. */
    private static LockValue lockOperand(AbstractInsnNode insn, FlowFrame<LockValue> before) {
        int op = insn.getOpcode();
        if (op == Opcodes.MONITORENTER || op == Opcodes.MONITOREXIT) {
            return before.peek(0);
        }
        if (insn instanceof MethodInsnNode mi && mi.desc.equals("()V") && LOCK_TYPES.contains(mi.owner)
                && (mi.name.equals("lock") || mi.name.equals("lockInterruptibly") || mi.name.equals("unlock"))) {
            return before.peek(0);
        }
        return null;
    }

    private static boolean isAcquire(AbstractInsnNode insn) {
        return insn.getOpcode() == Opcodes.MONITORENTER
                || insn instanceof MethodInsnNode mi && !mi.name.equals("unlock");
    }

    private static int index(List<String> labels, String label) {
        int i = labels.indexOf(label);
        if (i < 0) {
            labels.add(label);
            i = labels.size() - 1;
        }
        return i;
    }
}
