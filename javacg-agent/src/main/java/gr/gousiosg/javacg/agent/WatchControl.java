package gr.gousiosg.javacg.agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import gr.gousiosg.javacg.rt.Watch;
import gr.gousiosg.javacg.rt.WatchController;

/**
 * Method watches: a temporary transformer per watch that wraps the matching methods with
 * {@link Watch} probes, retransforms the loaded classes, and is removed again (retransforming
 * back) on stop or at its deadline, so a watch never outlives its duration.
 */
final class WatchControl implements WatchController {

    /** Packages a watch may not touch: the JDK (the probes use it) and javacg itself. */
    private static final List<String> FORBIDDEN = List.of("java.", "javax.", "jdk.", "sun.", "com.sun.", "gr.gousiosg.javacg.");
    private static final int MAX_CLASSES = 200;

    private final Instrumentation inst;
    private final Map<Integer, Active> active = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "javacg-watch-timer");
        t.setDaemon(true);
        return t;
    });

    private record Active(Watch.State state, ClassFileTransformer transformer, List<Class<?>> classes) {
    }

    WatchControl(Instrumentation inst) {
        this.inst = inst;
    }

    /** {@code target=pkg.Class.method[(types)];seconds=N;samples=N;capture=none|types|tostring;min_us=N;only_exceptions=true} */
    @Override
    public synchronized String start(String spec) {
        try {
            Map<String, String> o = parse(spec);
            String target = o.get("target");
            if (target == null || target.isBlank()) {
                return error("no target= (pkg.Class.method)");
            }
            String params = null;
            int paren = target.indexOf('(');
            if (paren >= 0) {
                params = target.substring(paren);
                target = target.substring(0, paren);
            }
            int dot = target.lastIndexOf('.');
            if (dot <= 0) {
                return error("target must be pkg.Class.method, got " + target);
            }
            String classGlob = target.substring(0, dot);
            String methodGlob = target.substring(dot + 1);
            for (String f : FORBIDDEN) {
                if (classGlob.startsWith(f)) {
                    return error("watching " + f + "* classes is not allowed");
                }
            }
            Pattern classes = glob(classGlob);
            Pattern methods = glob(methodGlob);
            String paramSpec = params;
            Watch.Capture capture = Watch.Capture.valueOf(o.getOrDefault("capture", "tostring").toUpperCase());
            int seconds = Integer.parseInt(o.getOrDefault("seconds", "20"));
            Watch.State state = Watch.create(target + (params == null ? "" : params), capture,
                    Integer.parseInt(o.getOrDefault("samples", "20")), Long.parseLong(o.getOrDefault("min_us", "0")) * 1000,
                    Boolean.parseBoolean(o.getOrDefault("only_exceptions", "false")));
            ClassFileTransformer t = new ClassFileTransformer() {
                @Override
                public byte[] transform(ClassLoader loader, String className, Class<?> redefined, ProtectionDomain pd,
                        byte[] bytes) {
                    if (className == null || !state.active() || !classes.matcher(className.replace('/', '.')).matches()) {
                        return null;
                    }
                    try {
                        return instrument(bytes, state, methods, paramSpec, capture != Watch.Capture.NONE);
                    } catch (Throwable e) {
                        System.err.println("[javacg] cannot watch " + className + ": " + e);
                        return null;
                    }
                }
            };
            List<Class<?>> loaded = new ArrayList<>();
            for (Class<?> c : inst.getAllLoadedClasses()) {
                if (!c.isArray() && !c.isPrimitive() && !c.isHidden() && inst.isModifiableClass(c)
                        && classes.matcher(c.getName()).matches()) {
                    loaded.add(c);
                }
            }
            if (loaded.size() > MAX_CLASSES) {
                state.stop();
                return error(loaded.size() + " loaded classes match " + classGlob + ": narrow the pattern (max " + MAX_CLASSES + ")");
            }
            inst.addTransformer(t, true);
            List<String> failures = new ArrayList<>();
            for (Class<?> c : loaded) {
                try {
                    inst.retransformClasses(c);
                } catch (Throwable e) {
                    failures.add(c.getName() + ": " + e);
                }
            }
            if (state.methodCount() == 0) {
                inst.removeTransformer(t);
                state.stop();
                return error(loaded.isEmpty() ? "no loaded class matches " + classGlob + " (is it loaded yet?)"
                        : "no method " + methodGlob + (params == null ? "" : params) + " in " + loaded.size() + " matching class(es)"
                                + (failures.isEmpty() ? "" : "; retransform failed: " + failures));
            }
            active.put(state.id(), new Active(state, t, loaded));
            timer.schedule(() -> stop(state.id()), Math.max(1, seconds), TimeUnit.SECONDS);
            return "{\"id\":\"" + state.id() + "\"}";
        } catch (RuntimeException e) {
            return error(e.toString());
        }
    }

    @Override
    public String result(String id, boolean stop) {
        int n;
        try {
            n = Integer.parseInt(id);
        } catch (NumberFormatException e) {
            return error("bad watch id " + id);
        }
        Watch.State s = Watch.state(n);
        if (s == null) {
            return error("no watch " + id);
        }
        if (stop) {
            stop(n);
        }
        return Watch.json(s);
    }

    @Override
    public String[] list() {
        List<String> ids = new ArrayList<>();
        for (Watch.State s : Watch.all()) {
            ids.add(s.id() + (s.active() ? " active" : " finished"));
        }
        return ids.toArray(new String[0]);
    }

    private synchronized void stop(int id) {
        Active a = active.remove(id);
        if (a == null) {
            return;
        }
        a.state().stop();
        inst.removeTransformer(a.transformer());
        for (Class<?> c : a.classes()) {
            try {
                inst.retransformClasses(c);
            } catch (Throwable e) {
                System.err.println("[javacg] cannot restore " + c.getName() + ": " + e);
            }
        }
    }

    static byte[] instrument(byte[] bytes, Watch.State state, Pattern methods, String params, boolean captureArgs) {
        ClassReader cr = new ClassReader(bytes);
        if ((cr.getAccess() & Opcodes.ACC_MODULE) != 0) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        boolean[] changed = { false };
        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            private String owner;
            private int version;

            @Override
            public void visit(int v, int access, String name, String signature, String superName, String[] interfaces) {
                owner = name;
                version = v & 0xFFFF;
                super.visit(v, access, name, signature, superName, interfaces);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (mv == null || (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_BRIDGE
                        | Opcodes.ACC_SYNTHETIC)) != 0 || name.equals("<clinit>") || !methods.matcher(name).matches()
                        || !paramsMatch(params, descriptor)) {
                    return mv;
                }
                changed[0] = true;
                state.instrumented(owner.replace('/', '.'), name + descriptor);
                return new WatchInstrumenter(mv, access, name, descriptor, state.id(), captureArgs, version);
            }
        }, ClassReader.EXPAND_FRAMES);
        return changed[0] ? cw.toByteArray() : null;
    }

    private static final Pattern JAVA_TYPES = Pattern.compile(".*(?:[.,]|\\b(?:int|long|short|byte|char|boolean|float|double)\\b).*");

    /**
     * Whether a method's descriptor fits the parameter part of a target: Java types
     * {@code (int,java.lang.String)} compared exactly, or a JVM descriptor prefix {@code (ILjava/lang/String;)}.
     */
    static boolean paramsMatch(String params, String descriptor) {
        if (params == null) {
            return true;
        }
        String p = params.replace(" ", "");
        if (!JAVA_TYPES.matcher(p).matches()) {
            return descriptor.startsWith(p);
        }
        StringBuilder sb = new StringBuilder("(");
        Type[] args = Type.getArgumentTypes(descriptor);
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(args[i].getClassName());
        }
        return p.equals(sb.append(')').toString());
    }

    /** {@code *} matches any characters, dots included. */
    static Pattern glob(String g) {
        StringBuilder sb = new StringBuilder();
        String[] parts = g.split("\\*", -1);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append(".*");
            }
            if (!parts[i].isEmpty()) {
                sb.append(Pattern.quote(parts[i]));
            }
        }
        return Pattern.compile(sb.toString());
    }

    private static Map<String, String> parse(String spec) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String part : spec.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                m.put(part.substring(0, eq).strip(), part.substring(eq + 1).strip());
            }
        }
        return m;
    }

    private static String error(String message) {
        StringBuilder sb = new StringBuilder("{\"error\":");
        quote(sb, message);
        return sb.append('}').toString();
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"').append(s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")).append('"');
    }
}
