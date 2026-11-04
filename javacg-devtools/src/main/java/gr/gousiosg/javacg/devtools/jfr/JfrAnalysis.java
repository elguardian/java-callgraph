package gr.gousiosg.javacg.devtools.jfr;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import gr.gousiosg.javacg.commons.stack.StackTrace;
import gr.gousiosg.javacg.commons.stack.StackTraces;
import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

/**
 * A summary of a JFR recording: CPU samples, lock contention, parking, allocation, I/O,
 * exceptions, GC and CPU load. Streams the file with the JDK's own {@code jdk.jfr.consumer}.
 */
public final class JfrAnalysis {

    /** A group of events: count, total and longest duration (ms), and an amount (bytes, weight). */
    public static final class Group {
        public final String key;
        public long count;
        public double totalMs;
        public double maxMs;
        public long amount;
        public String example;

        Group(String key) {
            this.key = key;
        }

        void add(double ms, long amount) {
            count++;
            totalMs += ms;
            maxMs = Math.max(maxMs, ms);
            this.amount += amount;
        }
    }

    /** What to read. */
    public record Options(Pattern threads, Instant from, Instant to, List<String> appPackages, int stackDepth) {
    }

    private Instant start;
    private Instant end;
    private final Map<String, Long> eventCounts = new HashMap<>();
    private long samples;
    private final Map<String, Group> hotSelf = new HashMap<>();
    private final Map<String, Group> hotTotal = new HashMap<>();
    private final Map<String, Group> hotApp = new HashMap<>();
    private final Map<String, Group> hotStacks = new HashMap<>();
    private final Map<String, Group> sampleThreads = new HashMap<>();
    private final Map<String, Group> monitors = new HashMap<>();
    private final Map<String, Group> parks = new HashMap<>();
    private final Map<String, Group> allocByClass = new HashMap<>();
    private final Map<String, Group> allocBySite = new HashMap<>();
    private final Map<String, Group> sockets = new HashMap<>();
    private final Map<String, Group> files = new HashMap<>();
    private final Map<String, Group> errors = new HashMap<>();
    private final Map<String, Group> gcs = new HashMap<>();
    private long throwablesFirst = -1;
    private long throwablesLast = -1;
    private double cpuJvmSum;
    private double cpuJvmMax;
    private double cpuMachineSum;
    private long cpuSamples;
    private final Options options;

    private JfrAnalysis(Options options) {
        this.options = options;
    }

    public static JfrAnalysis read(Path file, Options options) throws IOException {
        JfrAnalysis a = new JfrAnalysis(options);
        try (RecordingFile r = new RecordingFile(file)) {
            while (r.hasMoreEvents()) {
                a.accept(r.readEvent());
            }
        }
        return a;
    }

    private void accept(RecordedEvent e) {
        Instant t = e.getStartTime();
        if (options.from() != null && t.isBefore(options.from()) || options.to() != null && t.isAfter(options.to())) {
            return;
        }
        String type = e.getEventType().getName();
        eventCounts.merge(type, 1L, Long::sum);
        if (start == null || t.isBefore(start)) {
            start = t;
        }
        Instant stop = e.getEndTime();
        if (end == null || stop.isAfter(end)) {
            end = stop;
        }
        switch (type) {
            case "jdk.ExecutionSample" -> sample(e);
            case "jdk.JavaMonitorEnter" -> byFrame(e, monitors, "monitor " + className(e, "monitorClass"));
            case "jdk.ThreadPark" -> byFrame(e, parks, "park " + className(e, "parkedClass"));
            case "jdk.ObjectAllocationSample" -> alloc(e, e.hasField("weight") ? e.getLong("weight") : 0);
            case "jdk.ObjectAllocationInNewTLAB" -> alloc(e, e.hasField("tlabSize") ? e.getLong("tlabSize") : 0);
            case "jdk.SocketRead", "jdk.SocketWrite" -> io(e, sockets, (e.hasField("host") ? e.getString("host") : "?") + ":"
                    + (e.hasField("port") ? e.getInt("port") : 0), type.endsWith("Read") ? "bytesRead" : "bytesWritten");
            case "jdk.FileRead", "jdk.FileWrite", "jdk.FileForce" -> io(e, files, e.hasField("path") ? e.getString("path") : "?",
                    type.endsWith("Read") ? "bytesRead" : type.endsWith("Write") ? "bytesWritten" : null);
            case "jdk.JavaErrorThrow" -> {
                if (thread(e)) {
                    Group g = errors.computeIfAbsent(className(e, "thrownClass"), Group::new);
                    g.add(0, 0);
                    if (g.example == null && e.hasField("message")) {
                        g.example = e.getString("message");
                    }
                }
            }
            case "jdk.ExceptionStatistics" -> {
                long n = e.getLong("throwables");
                if (throwablesFirst < 0) {
                    throwablesFirst = n;
                }
                throwablesLast = n;
            }
            case "jdk.GarbageCollection" -> {
                Group g = gcs.computeIfAbsent(e.getString("name") + " (" + e.getString("cause") + ")", Group::new);
                g.add(millis(e.getDuration("sumOfPauses")), 0);
            }
            case "jdk.CPULoad" -> {
                double jvm = e.getFloat("jvmUser") + e.getFloat("jvmSystem");
                cpuJvmSum += jvm;
                cpuJvmMax = Math.max(cpuJvmMax, jvm);
                cpuMachineSum += e.getFloat("machineTotal");
                cpuSamples++;
            }
            default -> {
            }
        }
    }

    private void sample(RecordedEvent e) {
        RecordedThread th = e.hasField("sampledThread") ? e.getThread("sampledThread") : null;
        if (options.threads() != null && (th == null || !options.threads().matcher(name(th)).find())) {
            return;
        }
        List<RecordedFrame> frames = frames(e);
        if (frames.isEmpty()) {
            return;
        }
        samples++;
        add(hotSelf, label(frames.get(0)), 0, 0);
        Set<String> seen = new LinkedHashSet<>();
        for (RecordedFrame f : frames) {
            if (seen.add(label(f))) {
                add(hotTotal, label(f), 0, 0);
            }
        }
        String app = firstApp(frames);
        if (app != null) {
            add(hotApp, app, 0, 0);
        }
        add(hotStacks, stack(frames), 0, 0);
        if (th != null) {
            add(sampleThreads, name(th), 0, 0);
        }
    }

    private void byFrame(RecordedEvent e, Map<String, Group> into, String what) {
        if (!thread(e)) {
            return;
        }
        List<RecordedFrame> frames = frames(e);
        String where = firstApp(frames);
        if (where == null && !frames.isEmpty()) {
            where = label(frames.get(0));
        }
        Group g = into.computeIfAbsent(what + (where == null ? "" : " at " + where), Group::new);
        g.add(millis(e.getDuration()), 0);
        if (g.example == null && e.getThread() != null) {
            g.example = name(e.getThread());
        }
    }

    private void alloc(RecordedEvent e, long weight) {
        if (!thread(e)) {
            return;
        }
        add(allocByClass, className(e, "objectClass"), 0, weight);
        List<RecordedFrame> frames = frames(e);
        String site = firstApp(frames);
        if (site == null && !frames.isEmpty()) {
            site = label(frames.get(0));
        }
        if (site != null) {
            add(allocBySite, site, 0, weight);
        }
    }

    private void io(RecordedEvent e, Map<String, Group> into, String key, String bytesField) {
        if (!thread(e)) {
            return;
        }
        long bytes = bytesField != null && e.hasField(bytesField) ? e.getLong(bytesField) : 0;
        add(into, key, millis(e.getDuration()), Math.max(0, bytes));
    }

    private boolean thread(RecordedEvent e) {
        if (options.threads() == null) {
            return true;
        }
        RecordedThread t = e.getThread();
        return t != null && options.threads().matcher(name(t)).find();
    }

    private static void add(Map<String, Group> m, String key, double ms, long amount) {
        m.computeIfAbsent(key, Group::new).add(ms, amount);
    }

    private static List<RecordedFrame> frames(RecordedEvent e) {
        RecordedStackTrace st = e.getStackTrace();
        return st == null ? List.of() : st.getFrames();
    }

    private static String label(RecordedFrame f) {
        return f.getMethod().getType().getName() + "." + f.getMethod().getName();
    }

    /** The first frame of application code, or null (without app packages: the first non-JDK, non-framework frame). */
    private String firstApp(List<RecordedFrame> frames) {
        for (RecordedFrame f : frames) {
            StackTrace.Frame frame = new StackTrace.Frame(f.getMethod().getType().getName(), f.getMethod().getName(), null,
                    f.getLineNumber(), null);
            if (StackTraces.kind(frame, options.appPackages()) == StackTraces.Kind.APP) {
                return label(f) + (f.getLineNumber() > 0 ? ":" + f.getLineNumber() : "");
            }
        }
        return null;
    }

    private String stack(List<RecordedFrame> frames) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < Math.min(options.stackDepth(), frames.size()); i++) {
            parts.add(label(frames.get(i)));
        }
        return String.join(" <- ", parts);
    }

    private static String className(RecordedEvent e, String field) {
        if (!e.hasField(field)) {
            return "?";
        }
        RecordedClass c = e.getClass(field);
        return c == null ? "?" : javaName(c.getName());
    }

    /** {@code [B} → {@code byte[]}, {@code [Ljava.lang.String;} → {@code java.lang.String[]}. */
    static String javaName(String binary) {
        if (!binary.startsWith("[")) {
            return binary;
        }
        int dims = 0;
        while (dims < binary.length() && binary.charAt(dims) == '[') {
            dims++;
        }
        String rest = binary.substring(dims);
        String base = switch (rest) {
            case "B" -> "byte";
            case "C" -> "char";
            case "S" -> "short";
            case "I" -> "int";
            case "J" -> "long";
            case "F" -> "float";
            case "D" -> "double";
            case "Z" -> "boolean";
            default -> rest.startsWith("L") && rest.endsWith(";") ? rest.substring(1, rest.length() - 1) : rest;
        };
        return base + "[]".repeat(dims);
    }

    private static String name(RecordedThread t) {
        return t.getJavaName() != null ? t.getJavaName() : t.getOSName();
    }

    private static double millis(Duration d) {
        return d == null ? 0 : d.toNanos() / 1e6;
    }

    // ---- results

    public Instant start() {
        return start;
    }

    public Instant end() {
        return end;
    }

    public Map<String, Long> eventCounts() {
        return eventCounts;
    }

    public long samples() {
        return samples;
    }

    public List<Group> hotSelf(int n) {
        return top(hotSelf, n, g -> g.count);
    }

    public List<Group> hotTotal(int n) {
        return top(hotTotal, n, g -> g.count);
    }

    public List<Group> hotApp(int n) {
        return top(hotApp, n, g -> g.count);
    }

    public List<Group> hotStacks(int n) {
        return top(hotStacks, n, g -> g.count);
    }

    public List<Group> sampleThreads(int n) {
        return top(sampleThreads, n, g -> g.count);
    }

    public List<Group> monitors(int n) {
        return top(monitors, n, g -> g.totalMs);
    }

    public List<Group> parks(int n) {
        return top(parks, n, g -> g.totalMs);
    }

    public List<Group> allocByClass(int n) {
        return top(allocByClass, n, g -> g.amount);
    }

    public List<Group> allocBySite(int n) {
        return top(allocBySite, n, g -> g.amount);
    }

    public List<Group> sockets(int n) {
        return top(sockets, n, g -> g.totalMs);
    }

    public List<Group> files(int n) {
        return top(files, n, g -> g.totalMs);
    }

    public List<Group> errors(int n) {
        return top(errors, n, g -> g.count);
    }

    public List<Group> gcs(int n) {
        return top(gcs, n, g -> g.totalMs);
    }

    /** Throwables created during the recording (from jdk.ExceptionStatistics), or -1. */
    public long throwables() {
        return throwablesFirst < 0 ? -1 : throwablesLast - throwablesFirst;
    }

    /** Average and peak JVM CPU (user + system), and average machine CPU, as fractions; null without jdk.CPULoad. */
    public double[] cpuLoad() {
        return cpuSamples == 0 ? null : new double[] { cpuJvmSum / cpuSamples, cpuJvmMax, cpuMachineSum / cpuSamples };
    }

    private static List<Group> top(Map<String, Group> m, int n, java.util.function.ToDoubleFunction<Group> key) {
        return m.values().stream().sorted(Comparator.comparingDouble(key).reversed().thenComparing(g -> g.key)).limit(n).toList();
    }
}
