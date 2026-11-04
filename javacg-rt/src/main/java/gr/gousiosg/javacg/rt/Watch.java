package gr.gousiosg.javacg.rt;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * Statistics of watched methods, fed by bytecode the agent inserts while a watch is active:
 * {@code enter(args, id)} at entry, {@code exit(result, id)} before each return and
 * {@code thrown(t, id)} in a catch-all handler. Start times live on a per-thread stack, so the
 * inserted code needs no local variables. Nothing here throws into the application.
 */
public final class Watch {

    /** How argument and return values are kept in samples. */
    public enum Capture {
        NONE, TYPES, TOSTRING
    }

    private static final int MAX_CHARS = 200;
    private static final int SLOWEST = 5;
    private static final int BUCKETS = 16 + 48 * 8;

    private static volatile WatchController controller;
    private static volatile State[] states = new State[0];

    /** One active or finished watch. */
    public static final class State {
        final int id;
        final String target;
        final Capture capture;
        final int maxSamples;
        final long minNanos;
        final boolean onlyExceptions;
        final long startedMillis = System.currentTimeMillis();
        volatile long endedMillis;
        volatile boolean active = true;
        final LongAdder calls = new LongAdder();
        final LongAdder failed = new LongAdder();
        final LongAdder totalNanos = new LongAdder();
        final AtomicLong maxNanos = new AtomicLong();
        final AtomicLongArray histogram = new AtomicLongArray(BUCKETS);
        final Map<String, long[]> exceptions = new ConcurrentHashMap<>();
        final Map<String, String> exceptionSamples = new ConcurrentHashMap<>();
        final List<String> samples = new ArrayList<>();
        final long[] slowestNanos = new long[SLOWEST];
        final String[] slowest = new String[SLOWEST];
        final List<String> classes = new ArrayList<>();
        final List<String> methods = new ArrayList<>();

        State(int id, String target, Capture capture, int maxSamples, long minNanos, boolean onlyExceptions) {
            this.id = id;
            this.target = target;
            this.capture = capture;
            this.maxSamples = maxSamples;
            this.minNanos = minNanos;
            this.onlyExceptions = onlyExceptions;
        }

        public int id() {
            return id;
        }

        public boolean active() {
            return active;
        }

        public void stop() {
            active = false;
            endedMillis = System.currentTimeMillis();
        }

        /** Called by the transformer for each method it instruments. */
        public synchronized void instrumented(String className, String method) {
            if (!classes.contains(className)) {
                classes.add(className);
            }
            String m = className + "." + method;
            if (!methods.contains(m)) {
                methods.add(m);
            }
        }

        public synchronized int methodCount() {
            return methods.size();
        }
    }

    /** Per thread: the watched calls in progress. */
    private static final class Frames {
        int depth;
        int[] ids = new int[16];
        long[] starts = new long[16];
        Object[][] args = new Object[16][];
        boolean busy;
    }

    private static final ThreadLocal<Frames> FRAMES = ThreadLocal.withInitial(Frames::new);

    private Watch() {
    }

    public static void controller(WatchController c) {
        controller = c;
    }

    public static WatchController controller() {
        return controller;
    }

    /** Registers a new watch; ids index the state array the probes read. */
    public static synchronized State create(String target, Capture capture, int maxSamples, long minNanos,
            boolean onlyExceptions) {
        State[] old = states;
        State[] grown = new State[old.length + 1];
        System.arraycopy(old, 0, grown, 0, old.length);
        State s = new State(old.length, target, capture, maxSamples, minNanos, onlyExceptions);
        grown[old.length] = s;
        states = grown;
        return s;
    }

    public static State state(int id) {
        State[] s = states;
        return id >= 0 && id < s.length ? s[id] : null;
    }

    public static State[] all() {
        return states.clone();
    }

    // ---- probes

    public static void enter(Object[] args, int id) {
        try {
            Frames f = FRAMES.get();
            if (f.depth == f.ids.length) {
                f.ids = java.util.Arrays.copyOf(f.ids, f.depth * 2);
                f.starts = java.util.Arrays.copyOf(f.starts, f.depth * 2);
                f.args = java.util.Arrays.copyOf(f.args, f.depth * 2);
            }
            f.ids[f.depth] = id;
            f.args[f.depth] = args;
            f.starts[f.depth++] = System.nanoTime();
        } catch (Throwable t) {
            // never into the application
        }
    }

    public static void exit(Object result, int id) {
        finish(id, result, null);
    }

    public static void thrown(Throwable thrown, int id) {
        finish(id, null, thrown);
    }

    private static void finish(int id, Object result, Throwable thrown) {
        try {
            long now = System.nanoTime();
            Frames f = FRAMES.get();
            // unwind frames whose exit was lost (instrumentation added or removed mid-call)
            while (f.depth > 0 && f.ids[f.depth - 1] != id) {
                f.args[--f.depth] = null;
            }
            if (f.depth == 0) {
                return;
            }
            f.depth--;
            long nanos = now - f.starts[f.depth];
            Object[] args = f.args[f.depth];
            f.args[f.depth] = null;
            State s = state(id);
            if (s == null || !s.active || f.busy) {
                return;
            }
            f.busy = true;
            try {
                record(s, nanos, args, result, thrown);
            } finally {
                f.busy = false;
            }
        } catch (Throwable t) {
            // never into the application
        }
    }

    private static void record(State s, long nanos, Object[] args, Object result, Throwable thrown) {
        s.calls.increment();
        s.totalNanos.add(nanos);
        long max;
        while (nanos > (max = s.maxNanos.get()) && !s.maxNanos.compareAndSet(max, nanos)) {
            // retry
        }
        s.histogram.incrementAndGet(bucket(nanos / 1000));
        if (thrown != null) {
            s.failed.increment();
            String type = thrown.getClass().getName();
            s.exceptions.computeIfAbsent(type, k -> new long[1]);
            synchronized (s.exceptions) {
                s.exceptions.get(type)[0]++;
            }
            s.exceptionSamples.computeIfAbsent(type, k -> exceptionJson(thrown));
        }
        boolean qualifies = nanos >= s.minNanos && (!s.onlyExceptions || thrown != null);
        if (!qualifies) {
            return;
        }
        boolean sample;
        synchronized (s.samples) {
            sample = s.samples.size() < s.maxSamples;
        }
        int slot = -1;
        synchronized (s.slowest) {
            for (int i = 0; i < SLOWEST; i++) {
                if (s.slowest[i] == null || nanos > s.slowestNanos[i]) {
                    if (slot < 0 || s.slowest[slot] != null && (s.slowest[i] == null || s.slowestNanos[i] < s.slowestNanos[slot])) {
                        slot = i;
                    }
                }
            }
        }
        if (!sample && slot < 0) {
            return;
        }
        String json = sampleJson(s, nanos, args, result, thrown);
        if (sample) {
            synchronized (s.samples) {
                if (s.samples.size() < s.maxSamples) {
                    s.samples.add(json);
                }
            }
        }
        if (slot >= 0) {
            synchronized (s.slowest) {
                // recheck: the slot may have been taken meanwhile
                int target = -1;
                for (int i = 0; i < SLOWEST; i++) {
                    if (s.slowest[i] == null) {
                        target = i;
                        break;
                    }
                    if (target < 0 || s.slowestNanos[i] < s.slowestNanos[target]) {
                        target = i;
                    }
                }
                if (s.slowest[target] == null || nanos > s.slowestNanos[target]) {
                    s.slowest[target] = json;
                    s.slowestNanos[target] = nanos;
                }
            }
        }
    }

    /** Log-linear buckets: exact below 16 µs, then 8 per power of two (about 12% wide). */
    static int bucket(long micros) {
        if (micros < 16) {
            return (int) Math.max(0, micros);
        }
        int e = 63 - Long.numberOfLeadingZeros(micros);
        int sub = (int) ((micros >> (e - 3)) & 7);
        return Math.min(BUCKETS - 1, 16 + (e - 4) * 8 + sub);
    }

    /** Smallest value (µs) of a bucket. */
    static long lowerBound(int bucket) {
        if (bucket < 16) {
            return bucket;
        }
        int e = (bucket - 16) / 8 + 4;
        int sub = (bucket - 16) % 8;
        return (8L + sub) << (e - 3);
    }

    // ---- JSON

    private static String sampleJson(State s, long nanos, Object[] args, Object result, Throwable thrown) {
        StringBuilder sb = new StringBuilder("{");
        field(sb, "thread", Thread.currentThread().getName()).append(',');
        sb.append("\"at_ms\":").append(System.currentTimeMillis()).append(',');
        sb.append("\"duration_us\":").append(nanos / 1000);
        if (args != null && s.capture != Capture.NONE) {
            sb.append(",\"args\":[");
            for (int i = 0; i < args.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                string(sb, render(args[i], s.capture));
            }
            sb.append(']');
        }
        if (thrown != null) {
            sb.append(',');
            field(sb, "threw", thrown.getClass().getName() + (thrown.getMessage() == null ? "" : ": " + cut(thrown.getMessage())));
        } else if (s.capture != Capture.NONE) {
            sb.append(',');
            field(sb, "returned", result == VOID ? "void" : render(result, s.capture));
        }
        return sb.append('}').toString();
    }

    /** Marker the instrumented code passes as the result of void methods. */
    public static final Object VOID = new Object();

    private static String exceptionJson(Throwable t) {
        StringBuilder sb = new StringBuilder("{");
        field(sb, "message", t.getMessage() == null ? "" : cut(t.getMessage())).append(",\"at\":[");
        StackTraceElement[] st = t.getStackTrace();
        for (int i = 0; i < Math.min(5, st.length); i++) {
            if (i > 0) {
                sb.append(',');
            }
            string(sb, st[i].toString());
        }
        return sb.append("]}").toString();
    }

    private static String render(Object v, Capture capture) {
        if (v == null) {
            return "null";
        }
        if (capture == Capture.TYPES) {
            return v.getClass().getName();
        }
        try {
            if (v.getClass().isArray()) {
                int n = java.lang.reflect.Array.getLength(v);
                return v.getClass().getComponentType().getName() + "[" + n + "]";
            }
            return cut(String.valueOf(v));
        } catch (Throwable t) {
            return "<" + v.getClass().getName() + ".toString() threw " + t.getClass().getName() + ">";
        }
    }

    private static String cut(String s) {
        return s.length() <= MAX_CHARS ? s : s.substring(0, MAX_CHARS) + "…";
    }

    /** The results of a watch as JSON. */
    public static String json(State s) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"id\":").append(s.id).append(',');
        field(sb, "target", s.target).append(',');
        sb.append("\"active\":").append(s.active).append(',');
        sb.append("\"started_ms\":").append(s.startedMillis).append(',');
        sb.append("\"ended_ms\":").append(s.endedMillis == 0 ? System.currentTimeMillis() : s.endedMillis).append(',');
        synchronized (s) {
            list(sb.append("\"classes\":"), s.classes).append(',');
            list(sb.append("\"methods\":"), s.methods).append(',');
        }
        long calls = s.calls.sum();
        sb.append("\"calls\":").append(calls).append(',');
        sb.append("\"failed\":").append(s.failed.sum()).append(',');
        sb.append("\"total_us\":").append(s.totalNanos.sum() / 1000).append(',');
        sb.append("\"max_us\":").append(s.maxNanos.get() / 1000).append(',');
        for (int p : new int[] { 50, 90, 99 }) {
            sb.append("\"p").append(p).append("_us\":").append(percentile(s, calls, p)).append(',');
        }
        sb.append("\"exceptions\":[");
        boolean first = true;
        for (Map.Entry<String, long[]> e : s.exceptions.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('{');
            field(sb, "type", e.getKey()).append(",\"count\":").append(e.getValue()[0]);
            String sample = s.exceptionSamples.get(e.getKey());
            if (sample != null) {
                sb.append(",\"sample\":").append(sample);
            }
            sb.append('}');
        }
        sb.append("],\"samples\":[");
        synchronized (s.samples) {
            sb.append(String.join(",", s.samples));
        }
        sb.append("],\"slowest\":[");
        synchronized (s.slowest) {
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < SLOWEST; i++) {
                if (s.slowest[i] != null) {
                    order.add(i);
                }
            }
            order.sort((a, b) -> Long.compare(s.slowestNanos[b], s.slowestNanos[a]));
            for (int k = 0; k < order.size(); k++) {
                if (k > 0) {
                    sb.append(',');
                }
                sb.append(s.slowest[order.get(k)]);
            }
        }
        return sb.append("]}").toString();
    }

    private static long percentile(State s, long calls, int p) {
        if (calls == 0) {
            return 0;
        }
        long rank = (long) Math.ceil(calls * p / 100.0);
        long seen = 0;
        for (int i = 0; i < BUCKETS; i++) {
            seen += s.histogram.get(i);
            if (seen >= rank) {
                return lowerBound(i);
            }
        }
        return s.maxNanos.get() / 1000;
    }

    private static StringBuilder list(StringBuilder sb, List<String> values) {
        sb.append('[');
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            string(sb, values.get(i));
        }
        return sb.append(']');
    }

    private static StringBuilder field(StringBuilder sb, String name, String value) {
        string(sb, name);
        sb.append(':');
        string(sb, value);
        return sb;
    }

    static void string(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
