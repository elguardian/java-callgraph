package gr.gousiosg.javacg.analyze.detect;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicValue;

import gr.gousiosg.javacg.analyze.Model;
import gr.gousiosg.javacg.core.trace.TraceSummary;
import gr.gousiosg.javacg.stat.ClassHierarchy;
import gr.gousiosg.javacg.stat.ClassInfo;
import gr.gousiosg.javacg.stat.MethodInfo;
import gr.gousiosg.javacg.stat.flow.ControlFlowGraph;
import gr.gousiosg.javacg.stat.flow.FlowAnalysis;
import gr.gousiosg.javacg.stat.flow.FlowFrame;
import gr.gousiosg.javacg.stat.flow.TrackingInterpreter;

/**
 * Intraprocedural resource leaks. A resource is an {@code AutoCloseable} the method creates
 * ({@code new}, or a known factory such as {@code Files.newInputStream}). It stays open until
 * {@code close()} is called on it, or until it escapes: passed to another method or
 * constructor (which then owns it), stored in a field or array, captured by a lambda, or
 * returned. A resource still open at a {@code return} leaks on that path.
 *
 * <p>Decorators ({@code new BufferedReader(reader)}) only count as resources when they wrap
 * a resource this method opened, so wrapping a caller's stream or an in-memory buffer is not
 * reported.
 */
final class ResourceLeakDetector implements Detector {

    static final String LEAK = "resource.leak";
    static final String LEAK_ON_EXCEPTION = "resource.leak-on-exception";

    private static final String AUTO_CLOSEABLE = "java/lang/AutoCloseable";
    private static final Set<String> IN_MEMORY = Set.of("java/io/ByteArrayInputStream", "java/io/ByteArrayOutputStream",
            "java/io/StringReader", "java/io/StringWriter", "java/io/CharArrayReader", "java/io/CharArrayWriter",
            "java/util/Formatter", "java/io/PipedInputStream", "java/io/PipedOutputStream");
    private static final Set<String> STREAM_TYPES = Set.of("java/io/InputStream", "java/io/OutputStream",
            "java/io/Reader", "java/io/Writer", "java/nio/channels/Channel");

    /** owner, method name prefix: calls whose result the caller must close. */
    private static final String[][] FACTORIES = {
        {"java/nio/file/Files", "newInputStream"}, {"java/nio/file/Files", "newOutputStream"},
        {"java/nio/file/Files", "newBufferedReader"}, {"java/nio/file/Files", "newBufferedWriter"},
        {"java/nio/file/Files", "newByteChannel"}, {"java/nio/file/Files", "newDirectoryStream"},
        {"java/nio/file/Files", "lines"}, {"java/nio/file/Files", "list"}, {"java/nio/file/Files", "walk"},
        {"java/nio/file/Files", "find"},
        {"java/sql/Connection", "createStatement"}, {"java/sql/Connection", "prepareStatement"},
        {"java/sql/Connection", "prepareCall"}, {"java/sql/Statement", "executeQuery"},
        {"java/sql/DriverManager", "getConnection"}, {"javax/sql/DataSource", "getConnection"},
        {"java/net/URL", "openStream"}, {"java/net/ServerSocket", "accept"},
        {"java/nio/channels/FileChannel", "open"}, {"java/nio/channels/SocketChannel", "open"},
        {"java/lang/Class", "getResourceAsStream"}, {"java/lang/ClassLoader", "getResourceAsStream"},
    };

    @Override
    public String id() {
        return "resources";
    }

    @Override
    public Map<String, String> rules() {
        return Map.of(LEAK, "A resource opened by the method is not closed on some normal path",
                LEAK_ON_EXCEPTION, "A resource is not closed if a later call throws (no try/finally)");
    }

    /** Allocation sites (instruction indices) a reference may come from. */
    record SiteValue(BasicValue basic, BitSet sites) implements TrackingInterpreter.Tracked {
    }

    private static final class Interp extends TrackingInterpreter<SiteValue> {
        private static final BitSet NONE = new BitSet();
        private final InsnList insns;
        private final ResourceLeakDetector owner;
        private final ClassHierarchy hierarchy;

        Interp(InsnList insns, ResourceLeakDetector owner, ClassHierarchy hierarchy) {
            this.insns = insns;
            this.owner = owner;
            this.hierarchy = hierarchy;
        }

        @Override
        protected SiteValue plain(BasicValue b) {
            return new SiteValue(b, NONE);
        }

        @Override
        protected SiteValue created(AbstractInsnNode insn, BasicValue b) {
            return insn.getOpcode() == Opcodes.NEW ? site(insn, b) : plain(b);
        }

        @Override
        protected SiteValue unary(AbstractInsnNode insn, SiteValue v, BasicValue b) {
            return insn.getOpcode() == Opcodes.CHECKCAST ? v : plain(b);
        }

        @Override
        protected SiteValue nary(AbstractInsnNode insn, List<? extends SiteValue> args, BasicValue b) {
            return insn instanceof MethodInsnNode mi && owner.isFactory(mi, hierarchy) ? site(insn, b) : plain(b);
        }

        @Override
        protected SiteValue join(SiteValue a, SiteValue b, BasicValue merged) {
            BitSet s = (BitSet) a.sites.clone();
            s.or(b.sites);
            return new SiteValue(merged, s);
        }

        private SiteValue site(AbstractInsnNode insn, BasicValue b) {
            BitSet s = new BitSet();
            s.set(insns.indexOf(insn));
            return new SiteValue(b, s);
        }
    }

    @Override
    public List<Finding> run(Model model) {
        if (!model.hasStatic()) {
            return List.of();
        }
        List<Finding> findings = new ArrayList<>();
        for (ClassInfo c : model.statics().appClasses()) {
            for (MethodInfo m : c.methods()) {
                if (Bytecode.analyzable(c, m)) {
                    analyze(model, c, m, findings);
                }
            }
        }
        return findings;
    }

    private void analyze(Model model, ClassInfo c, MethodInfo m, List<Finding> out) {
        MethodNode node = m.node();
        InsnList insns = node.instructions;
        ClassHierarchy hierarchy = model.statics().hierarchy();
        ControlFlowGraph cfg = ControlFlowGraph.of(node);
        Map<Integer, Integer> leaks = new TreeMap<>();            // site -> return line
        Map<Integer, Integer> leaksOnException = new TreeMap<>(); // site -> throwing call line
        Map<Integer, BitSet> wrappers = new HashMap<>();          // site -> decorators opened around it
        FlowAnalysis.Hooks<SiteValue> hooks = new FlowAnalysis.Hooks<>() {
            @Override
            public void afterExecute(int index, AbstractInsnNode insn, FlowFrame<SiteValue> before,
                    FlowFrame<SiteValue> after) {
                BitSet open = after.facts();
                int op = insn.getOpcode();
                if (insn instanceof MethodInsnNode mi) {
                    int n = Bytecode.operandCount(mi);
                    boolean isStatic = op == Opcodes.INVOKESTATIC;
                    SiteValue receiver = isStatic ? null : before.peek(n - 1);
                    boolean argWasOpen = false;
                    for (int k = 0; k < (isStatic ? n : n - 1); k++) {
                        BitSet argSites = before.peek(k).sites();
                        argWasOpen |= argSites.intersects(before.facts());
                        open.andNot(argSites); // the callee owns it now
                    }
                    if (op == Opcodes.INVOKESPECIAL && mi.name.equals("<init>") && receiver != null) {
                        boolean wrapper = isWrapper(mi.desc, hierarchy);
                        if (isResource(mi.owner, hierarchy) && (!wrapper || argWasOpen)) {
                            BitSet created = new BitSet();
                            receiver.sites().stream()
                                    .filter(s -> insns.get(s) instanceof TypeInsnNode t && t.desc.equals(mi.owner))
                                    .forEach(created::set);
                            open.or(created);
                            for (int k = 0; k < n - 1; k++) {
                                before.peek(k).sites().stream().filter(before.facts()::get)
                                        .forEach(a -> wrappers.computeIfAbsent(a, x -> new BitSet()).or(created));
                            }
                        }
                    } else if (mi.name.equals("close") && mi.desc.equals("()V") && receiver != null) {
                        close(open, receiver.sites(), wrappers);
                    }
                    if (isFactory(mi, hierarchy)) {
                        open.set(index);
                    }
                } else if (insn instanceof InvokeDynamicInsnNode indy) {
                    int n = Type.getArgumentTypes(indy.desc).length;
                    for (int k = 0; k < n; k++) {
                        open.andNot(before.peek(k).sites());
                    }
                } else if (op == Opcodes.PUTFIELD || op == Opcodes.PUTSTATIC || op == Opcodes.AASTORE) {
                    open.andNot(before.peek(0).sites());
                }
            }

            @Override
            public void onException(int index, AbstractInsnNode insn, ControlFlowGraph.Handler handler,
                    FlowFrame<SiteValue> before, FlowFrame<SiteValue> edge) {
                // a close() that throws still counts as closing (try-with-resources then adds it as suppressed)
                if (insn instanceof MethodInsnNode mi && mi.name.equals("close") && mi.desc.equals("()V")
                        && mi.getOpcode() != Opcodes.INVOKESTATIC) {
                    close(edge.facts(), before.peek(0).sites(), wrappers);
                }
            }

            @Override
            public void onBranch(int index, AbstractInsnNode insn, boolean taken, FlowFrame<SiteValue> before,
                    FlowFrame<SiteValue> edge) {
                int op = insn.getOpcode();
                if (op == Opcodes.IFNULL && taken || op == Opcodes.IFNONNULL && !taken) {
                    close(edge.facts(), before.peek(0).sites(), wrappers); // null on this edge: nothing to close
                }
            }
        };
        FlowFrame<SiteValue>[] frames;
        try {
            frames = new FlowAnalysis<>(new Interp(insns, this, hierarchy), hooks).analyze(c.name(), node, cfg);
        } catch (AnalyzerException | UnsupportedOperationException e) {
            return;
        }
        // report from the fixed point only: earlier visits see incomplete value information
        for (int i = 0; i < frames.length; i++) {
            FlowFrame<SiteValue> before = frames[i];
            if (before == null || before.facts().isEmpty()) {
                continue;
            }
            AbstractInsnNode insn = insns.get(i);
            int op = insn.getOpcode();
            if (op >= Opcodes.IRETURN && op <= Opcodes.RETURN) {
                BitSet leaked = (BitSet) before.facts().clone();
                if (op == Opcodes.ARETURN) {
                    leaked.andNot(before.peek(0).sites());
                }
                leaked.stream().forEach(s -> leaks.putIfAbsent(s, Bytecode.line(insn)));
            } else if (insn instanceof MethodInsnNode mi && cfg.handlers(i).isEmpty()) {
                // if this call throws, whatever is still open (and not handed to the call) leaks
                int n = Bytecode.operandCount(mi);
                BitSet atRisk = (BitSet) before.facts().clone();
                for (int k = 0; k < n; k++) {
                    boolean isReceiver = mi.getOpcode() != Opcodes.INVOKESTATIC && k == n - 1;
                    if (!isReceiver) {
                        atRisk.andNot(before.peek(k).sites());
                    } else if (mi.name.equals("close")) {
                        close(atRisk, before.peek(k).sites(), wrappers);
                    }
                }
                atRisk.stream().forEach(s -> leaksOnException.putIfAbsent(s, Bytecode.line(insn)));
            }
        }
        TraceSummary.MethodStats rt = model.runtime(m.id());
        List<Finding.Location> ran = rt == null ? List.of()
                : List.of(new Finding.Location(m.id(), -1, "method ran " + rt.calls() + " time(s) at runtime"));
        leaks.forEach((site, returnLine) -> {
            List<Finding.Location> related = new ArrayList<>();
            related.add(new Finding.Location(m.id(), returnLine, "still open at this return"));
            related.addAll(ran);
            out.add(new Finding(LEAK, Finding.Severity.MEDIUM, Finding.Confidence.MEDIUM,
                    describe(insns, site) + " is not closed on every path; use try-with-resources", m.id(),
                    Bytecode.line(insns.get(site)), related, List.of()));
        });
        leaksOnException.forEach((site, callLine) -> {
            if (!leaks.containsKey(site)) {
                out.add(new Finding(LEAK_ON_EXCEPTION, Finding.Severity.LOW, Finding.Confidence.MEDIUM,
                        describe(insns, site) + " is not closed if the call at line " + callLine
                                + " throws; use try-with-resources",
                        m.id(), Bytecode.line(insns.get(site)),
                        List.of(new Finding.Location(m.id(), callLine, "may throw while open")), List.of()));
            }
        });
    }

    /** Closes the given sites and every decorator opened around them (closing either releases both). */
    private static void close(BitSet open, BitSet sites, Map<Integer, BitSet> wrappers) {
        BitSet todo = (BitSet) sites.clone();
        BitSet done = new BitSet();
        while (!todo.isEmpty()) {
            int s = todo.nextSetBit(0);
            todo.clear(s);
            if (!done.get(s)) {
                done.set(s);
                open.clear(s);
                BitSet w = wrappers.get(s);
                if (w != null) {
                    todo.or(w);
                }
            }
        }
    }

    private static String describe(InsnList insns, int site) {
        AbstractInsnNode insn = insns.get(site);
        if (insn instanceof TypeInsnNode t) {
            return Type.getObjectType(t.desc).getClassName() + " created here";
        }
        MethodInsnNode mi = (MethodInsnNode) insn;
        return Type.getReturnType(mi.desc).getClassName() + " from " + Type.getObjectType(mi.owner).getClassName()
                + "." + mi.name + "()";
    }

    boolean isFactory(MethodInsnNode mi, ClassHierarchy hierarchy) {
        for (String[] f : FACTORIES) {
            if (mi.name.equals(f[1]) && (mi.owner.equals(f[0]) || hierarchy.isSubtypeOf(mi.owner, f[0]))) {
                return Type.getReturnType(mi.desc).getSort() == Type.OBJECT;
            }
        }
        return false;
    }

    private static boolean isResource(String type, ClassHierarchy hierarchy) {
        return !IN_MEMORY.contains(type) && !type.startsWith("java/util/stream/")
                && hierarchy.isSubtypeOf(type, AUTO_CLOSEABLE) && !type.equals("java/lang/Object");
    }

    /** A constructor taking a stream, reader, writer, channel or other closeable: a decorator. */
    private static boolean isWrapper(String ctorDesc, ClassHierarchy hierarchy) {
        for (Type t : Type.getArgumentTypes(ctorDesc)) {
            if (t.getSort() == Type.OBJECT && (STREAM_TYPES.contains(t.getInternalName())
                    || hierarchy.isSubtypeOf(t.getInternalName(), AUTO_CLOSEABLE))) {
                return true;
            }
        }
        return false;
    }
}
