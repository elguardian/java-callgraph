package gr.gousiosg.javacg.stat;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

import gr.gousiosg.javacg.core.CallGraph;
import gr.gousiosg.javacg.core.CallKind;
import gr.gousiosg.javacg.core.MethodId;

/**
 * Builds the static call graph of application classes.
 *
 * <pre>
 * StaticResult r = StaticAnalyzer.create().app(Path.of("app.war")).lib(libs).run();
 * </pre>
 */
public final class StaticAnalyzer {

    private static final String LAMBDA_METAFACTORY = "java/lang/invoke/LambdaMetafactory";

    private final List<Path> app = new ArrayList<>();
    private final List<Path> lib = new ArrayList<>();
    private Algorithm algorithm = Algorithm.CHA;
    private boolean jdkFallback = true;

    private ClassHierarchy hierarchy;
    private Set<String> instantiated;
    private final Map<String, Set<MethodId>> targetCache = new HashMap<>();

    private StaticAnalyzer() {
    }

    public static StaticAnalyzer create() {
        return new StaticAnalyzer();
    }

    /** Application inputs: jars, class directories, single class files, WAR/EAR archives. */
    public StaticAnalyzer app(Path... inputs) {
        return app(List.of(inputs));
    }

    public StaticAnalyzer app(Collection<Path> inputs) {
        app.addAll(inputs);
        return this;
    }

    /** Library inputs: used for the hierarchy and call resolution, bodies are not analyzed. */
    public StaticAnalyzer lib(Path... inputs) {
        return lib(List.of(inputs));
    }

    public StaticAnalyzer lib(Collection<Path> inputs) {
        lib.addAll(inputs);
        return this;
    }

    public StaticAnalyzer algorithm(Algorithm a) {
        this.algorithm = a;
        return this;
    }

    /** Whether to load missing classes from the running JDK (default true). */
    public StaticAnalyzer jdkFallback(boolean enabled) {
        this.jdkFallback = enabled;
        return this;
    }

    /**
     * Runs the analysis.
     *
     * @throws NoSuchFileException listing every input that does not exist; nothing is analyzed
     */
    public StaticResult run() throws IOException {
        List<Path> all = new ArrayList<>(app);
        all.addAll(lib);
        List<Path> missing = ClassPathReader.missing(all);
        if (!missing.isEmpty()) {
            throw new NoSuchFileException(missing.stream().map(Path::toString).collect(Collectors.joining(", ")),
                    null, "input does not exist");
        }

        List<String> warnings = new ArrayList<>();
        hierarchy = new ClassHierarchy(jdkFallback);
        ClassPathReader reader = new ClassPathReader(warnings);
        List<ClassInfo> appClasses = new ArrayList<>();
        for (Path p : app) {
            reader.read(p, ClassScope.APP, c -> {
                if (hierarchy.add(c)) {
                    appClasses.add(c);
                } else {
                    warnings.add("Duplicate class " + c.name() + " in " + c.origin() + " ignored");
                }
            });
        }
        for (Path p : lib) {
            reader.read(p, ClassScope.LIB, c -> {
                if (!hierarchy.add(c)) {
                    warnings.add("Duplicate class " + c.name() + " in " + c.origin() + " ignored");
                }
            });
        }
        appClasses.sort(Comparator.comparing(ClassInfo::name));

        instantiated = algorithm == Algorithm.RTA ? instantiatedClasses(appClasses) : null;

        CallGraph graph = new CallGraph();
        List<CallSite> sites = new ArrayList<>();
        for (ClassInfo c : appClasses) {
            for (MethodInfo m : c.methods()) {
                graph.addMethod(m.id());
                if (m.hasCode()) {
                    scan(m, graph, sites);
                }
            }
        }
        return new StaticResult(hierarchy, graph, sites, appClasses, warnings, algorithm);
    }

    private void scan(MethodInfo m, CallGraph graph, List<CallSite> sites) {
        int line = -1;
        int index = 0;
        for (AbstractInsnNode insn = m.node().instructions.getFirst(); insn != null; insn = insn.getNext(), index++) {
            if (insn instanceof LineNumberNode ln) {
                line = ln.line;
            } else if (insn instanceof MethodInsnNode mi) {
                CallKind kind = switch (mi.getOpcode()) {
                    case Opcodes.INVOKEVIRTUAL -> CallKind.VIRTUAL;
                    case Opcodes.INVOKEINTERFACE -> CallKind.INTERFACE;
                    case Opcodes.INVOKESPECIAL -> CallKind.SPECIAL;
                    default -> CallKind.STATIC;
                };
                Set<MethodId> targets = targets(kind, mi.owner, mi.name, mi.desc);
                record(m, index, line, kind, mi.owner, mi.name, mi.desc, targets, graph, sites);
            } else if (insn instanceof InvokeDynamicInsnNode indy) {
                Set<MethodId> targets = Set.of();
                Handle impl = lambdaImplementation(indy);
                if (impl != null) {
                    CallKind implKind = switch (impl.getTag()) {
                        case Opcodes.H_INVOKEVIRTUAL -> CallKind.VIRTUAL;
                        case Opcodes.H_INVOKEINTERFACE -> CallKind.INTERFACE;
                        case Opcodes.H_INVOKESTATIC -> CallKind.STATIC;
                        default -> CallKind.SPECIAL;
                    };
                    targets = targets(implKind, impl.getOwner(), impl.getName(), impl.getDesc());
                }
                String produced = Type.getReturnType(indy.desc).getInternalName();
                record(m, index, line, CallKind.DYNAMIC, produced, indy.name, indy.desc, targets, graph, sites);
            }
        }
    }

    private static void record(MethodInfo m, int index, int line, CallKind kind, String owner, String name,
            String desc, Set<MethodId> targets, CallGraph graph, List<CallSite> sites) {
        sites.add(new CallSite(m.id(), index, line, kind, owner, name, desc, targets));
        for (MethodId t : targets) {
            graph.addStaticCall(m.id(), t, kind, line);
        }
    }

    /** The implementation method handle of a lambda / method reference call site, or null. */
    private static Handle lambdaImplementation(InvokeDynamicInsnNode indy) {
        if (!indy.bsm.getOwner().equals(LAMBDA_METAFACTORY) || indy.bsmArgs.length < 2
                || !(indy.bsmArgs[1] instanceof Handle h)) {
            return null;
        }
        return h;
    }

    private Set<MethodId> targets(CallKind kind, String owner, String name, String desc) {
        String key = kind.code() + owner + '.' + name + desc;
        Set<MethodId> cached = targetCache.get(key);
        if (cached == null) {
            cached = computeTargets(kind, owner, name, desc);
            targetCache.put(key, cached);
        }
        return cached;
    }

    private Set<MethodId> computeTargets(CallKind kind, String owner, String name, String desc) {
        MethodId reference = MethodId.of(owner, name, desc);
        MethodInfo resolved = hierarchy.resolve(owner, name, desc);
        if (resolved == null) {
            return Set.of(reference);
        }
        if (kind == CallKind.STATIC || kind == CallKind.SPECIAL || resolved.isPrivate() || resolved.isFinal()
                || resolved.owner().isFinal() && !resolved.owner().isInterface()) {
            return Set.of(resolved.id());
        }
        Set<MethodId> result = new LinkedHashSet<>();
        for (ClassInfo s : hierarchy.subtypes(owner)) {
            if (!s.isConcrete() || instantiated != null && s.scope() == ClassScope.APP && !instantiated.contains(s.name())) {
                continue;
            }
            MethodInfo target = hierarchy.dispatch(s.name(), name, desc);
            if (target != null) {
                result.add(target.id());
            }
        }
        if (result.isEmpty()) {
            result.add(resolved.id());
        }
        return Set.copyOf(result);
    }

    private static Set<String> instantiatedClasses(List<ClassInfo> appClasses) {
        Set<String> result = new HashSet<>();
        for (ClassInfo c : appClasses) {
            for (MethodInfo m : c.methods()) {
                if (!m.hasCode()) {
                    continue;
                }
                for (AbstractInsnNode insn : m.node().instructions) {
                    if (insn.getOpcode() == Opcodes.NEW) {
                        result.add(((TypeInsnNode) insn).desc);
                    } else if (insn instanceof InvokeDynamicInsnNode indy) {
                        Handle h = lambdaImplementation(indy);
                        if (h != null && h.getTag() == Opcodes.H_NEWINVOKESPECIAL) {
                            result.add(h.getOwner());
                        }
                    }
                }
            }
        }
        return result;
    }
}
