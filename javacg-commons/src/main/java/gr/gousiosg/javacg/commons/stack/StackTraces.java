package gr.gousiosg.javacg.commons.stack;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

/** Classifying, trimming, rendering and grouping stack traces. */
public final class StackTraces {

    /** Test runners, build tools, reflection and container plumbing: rarely where a bug is. */
    public static final List<String> FRAMEWORK_PREFIXES = List.of("org.junit.", "junit.", "org.opentest4j.",
            "org.apiguardian.", "org.apache.maven.", "org.codehaus.plexus.", "org.gradle.", "worker.org.gradle.",
            "com.intellij.", "org.eclipse.jdt.internal.junit", "sun.reflect.", "jdk.internal.", "java.lang.reflect.",
            "java.lang.invoke.", "jdk.proxy", "com.sun.proxy.", "org.jboss.arquillian.", "org.testng.",
            "org.assertj.core.internal.", "org.assertj.core.api.", "org.hamcrest.", "org.mockito.internal.",
            "net.bytebuddy.", "org.springframework.test.", "java.util.concurrent.ThreadPoolExecutor",
            "java.util.concurrent.FutureTask", "java.lang.Thread.run");

    private static final List<String> JDK_PREFIXES = List.of("java.", "javax.", "jdk.", "sun.", "com.sun.");

    private StackTraces() {
    }

    public enum Kind {
        APP, LIBRARY, JDK, FRAMEWORK
    }

    /**
     * How a frame is classified: {@code appPackages} empty means every non-JDK, non-framework frame
     * is application code.
     */
    public static Kind kind(StackTrace.Frame f, List<String> appPackages) {
        String c = f.className();
        if (appPackages.stream().anyMatch(p -> c.startsWith(p.endsWith(".") ? p : p + "."))
                || appPackages.contains(c)) {
            return Kind.APP;
        }
        if (FRAMEWORK_PREFIXES.stream().anyMatch(c::startsWith)) {
            return Kind.FRAMEWORK;
        }
        if (JDK_PREFIXES.stream().anyMatch(c::startsWith)) {
            return Kind.JDK;
        }
        return appPackages.isEmpty() ? Kind.APP : Kind.LIBRARY;
    }

    /** The first frame of the trace or its causes classified as application code, if any. */
    public static StackTrace.Frame firstAppFrame(StackTrace t, List<String> appPackages) {
        for (StackTrace s : rootFirst(t)) {
            for (StackTrace.Frame f : s.frames()) {
                if (kind(f, appPackages) == Kind.APP) {
                    return f;
                }
            }
        }
        return null;
    }

    /**
     * Text in {@code printStackTrace} form. With {@code trim}, framework frames (and, when
     * {@code appPackages} is given, runs of library/JDK frames between application frames) are
     * collapsed to one {@code ... N frames} line; the first frame of each trace is always kept.
     */
    public static String render(StackTrace t, boolean trim, List<String> appPackages) {
        StringBuilder sb = new StringBuilder();
        render(sb, t, "", "", trim, appPackages);
        return sb.toString();
    }

    private static void render(StringBuilder sb, StackTrace t, String indent, String prefix, boolean trim,
            List<String> appPackages) {
        sb.append(indent).append(prefix).append(t.header()).append('\n');
        List<StackTrace.Frame> frames = t.frames();
        int hidden = 0;
        List<String> hiddenWhat = new ArrayList<>();
        for (int i = 0; i < frames.size(); i++) {
            StackTrace.Frame f = frames.get(i);
            Kind k = kind(f, appPackages);
            boolean keep = !trim || i == 0 || k == Kind.APP || k == Kind.LIBRARY && appPackages.isEmpty();
            if (keep) {
                hidden = flush(sb, indent, hidden, hiddenWhat);
                sb.append(indent).append("\tat ").append(f).append('\n');
            } else {
                hidden++;
                String pkg = top(f.className());
                if (!hiddenWhat.contains(pkg)) {
                    hiddenWhat.add(pkg);
                }
            }
        }
        flush(sb, indent, hidden, hiddenWhat);
        if (t.omitted() > 0) {
            sb.append(indent).append("\t... ").append(t.omitted()).append(" more\n");
        }
        for (StackTrace s : t.suppressed()) {
            render(sb, s, indent + "\t", "Suppressed: ", trim, appPackages);
        }
        if (t.cause() != null) {
            render(sb, t.cause(), indent, "Caused by: ", trim, appPackages);
        }
    }

    private static int flush(StringBuilder sb, String indent, int hidden, List<String> what) {
        if (hidden > 0) {
            sb.append(indent).append("\t... ").append(hidden).append(hidden == 1 ? " frame" : " frames")
                    .append(" (").append(String.join(", ", what.subList(0, Math.min(3, what.size()))))
                    .append(what.size() > 3 ? ", ..." : "").append(")\n");
            what.clear();
        }
        return 0;
    }

    private static String top(String cls) {
        String[] parts = cls.split("\\.");
        return parts.length <= 2 ? cls : parts[0] + "." + parts[1];
    }

    /**
     * What makes two failures "the same": the outer and root exception classes and the first
     * {@code depth} application frames of the root cause (method names, not lines, so it survives
     * edits). Messages are left out: they often carry ids and values.
     */
    public static String signature(StackTrace t, List<String> appPackages, int depth) {
        StackTrace root = t.root();
        List<String> where = new ArrayList<>();
        for (StackTrace s : rootFirst(t)) {
            for (StackTrace.Frame f : s.frames()) {
                if (where.size() < depth && kind(f, appPackages) == Kind.APP && !where.contains(f.location())) {
                    where.add(f.location());
                }
            }
        }
        if (where.isEmpty() && !root.frames().isEmpty()) {
            where.add(root.frames().get(0).location());
        }
        String types = t.exception().equals(root.exception()) ? t.exception() : t.exception() + " <- " + root.exception();
        return types + (where.isEmpty() ? "" : " at " + String.join(" < ", where));
    }

    /** A short stable id for a signature. */
    public static String signatureId(String signature) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-1").digest(signature.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** One line: root exception and message, and the first application frame. */
    public static String oneLine(StackTrace t, List<String> appPackages) {
        StackTrace root = t.root();
        StackTrace.Frame f = firstAppFrame(t, appPackages);
        String msg = root.message() == null ? "" : ": " + root.message().lines().findFirst().orElse("");
        return root.exception() + msg + (f == null ? "" : " (" + f.location() + ":" + f.line() + ")");
    }

    /** The cause chain innermost first: the root cause's frames are nearest to where it went wrong. */
    private static List<StackTrace> rootFirst(StackTrace t) {
        List<StackTrace> chain = new ArrayList<>(t.chain());
        java.util.Collections.reverse(chain);
        return chain;
    }

    static String joinTypes(StackTrace t) {
        return t.chain().stream().map(StackTrace::exception).collect(Collectors.joining(" <- "));
    }
}
