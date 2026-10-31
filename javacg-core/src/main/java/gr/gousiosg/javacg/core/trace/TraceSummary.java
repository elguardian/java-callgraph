package gr.gousiosg.javacg.core.trace;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import gr.gousiosg.javacg.core.CallGraph;
import gr.gousiosg.javacg.core.Edge;
import gr.gousiosg.javacg.core.MethodId;

/**
 * One-pass summary of a recording: the runtime call graph (caller frame → callee frame, with
 * counts) and per-method statistics. Replaces the original call-pair summary and
 * {@code process_trace.rb}.
 */
public final class TraceSummary {

    /** Per-method runtime statistics. Times are in nanoseconds. */
    public static final class MethodStats {
        long calls;
        long totalNanos;
        long selfNanos;
        int maxRecursion;
        final Map<String, Long> exceptions = new TreeMap<>();
        final Map<String, Long> thrownHere = new TreeMap<>();
        final Map<String, Long> caught = new TreeMap<>();

        public long calls() {
            return calls;
        }

        /** Inclusive time; recursive invocations are counted at every level. */
        public long totalNanos() {
            return totalNanos;
        }

        /** Exclusive time: inclusive time minus the time of instrumented callees. */
        public long selfNanos() {
            return selfNanos;
        }

        /** Largest number of simultaneous frames of this method on one thread's stack. */
        public int maxRecursion() {
            return maxRecursion;
        }

        /** Exceptional exits by exception class. */
        public Map<String, Long> exceptions() {
            return Collections.unmodifiableMap(exceptions);
        }

        /**
         * Exceptional exits where no instrumented callee threw the same exception class just
         * before: thrown by this method, or by uninstrumented code it called.
         */
        public Map<String, Long> thrownHere() {
            return Collections.unmodifiableMap(thrownHere);
        }

        /** Exceptions thrown by an instrumented callee that this method caught (it carried on). */
        public Map<String, Long> caught() {
            return Collections.unmodifiableMap(caught);
        }

        void add(MethodStats o) {
            calls += o.calls;
            totalNanos += o.totalNanos;
            selfNanos += o.selfNanos;
            maxRecursion = Math.max(maxRecursion, o.maxRecursion);
            o.exceptions.forEach((k, v) -> exceptions.merge(k, v, Long::sum));
            o.thrownHere.forEach((k, v) -> thrownHere.merge(k, v, Long::sum));
            o.caught.forEach((k, v) -> caught.merge(k, v, Long::sum));
        }

        /** Sum of several recordings' statistics for one method. */
        public static MethodStats sum(Iterable<MethodStats> all) {
            MethodStats total = new MethodStats();
            all.forEach(total::add);
            return total;
        }
    }

    private final Recording recording;
    private final CallGraph graph = new CallGraph();
    private final Map<MethodId, MethodStats> stats = new HashMap<>();
    /** Caller id (high 32 bits) and callee id (low 32 bits) → count, folded into the graph at the end. */
    private final Map<Long, long[]> pairs = new HashMap<>();
    private long unmatchedExits;
    private long unwoundFrames;
    private int maxDepth;

    private TraceSummary(Recording recording) {
        this.recording = recording;
    }

    public static TraceSummary of(Recording recording) throws IOException {
        TraceSummary s = new TraceSummary(recording);
        Map<Long, Map<Integer, Integer>> onStack = new HashMap<>();
        CallStacks stacks = new CallStacks(new CallStacks.Listener() {
            @Override
            public void onEnter(long thread, CallStacks.Frame frame, List<CallStacks.Frame> stack) {
                MethodId m = recording.method(frame.method());
                s.graph.addMethod(m);
                MethodStats st = s.stats(m);
                st.calls++;
                int depth = onStack.computeIfAbsent(thread, k -> new HashMap<>()).merge(frame.method(), 1, Integer::sum);
                st.maxRecursion = Math.max(st.maxRecursion, depth);
                s.maxDepth = Math.max(s.maxDepth, stack.size() + 1);
                if (!stack.isEmpty()) {
                    long key = (long) stack.get(stack.size() - 1).method() << 32 | frame.method() & 0xFFFFFFFFL;
                    s.addPair(key);
                }
            }

            @Override
            public void onExit(long thread, CallStacks.Frame frame, List<CallStacks.Frame> stack, CallStacks.Exit how,
                    long exitNanos, int exceptionClass) {
                MethodStats st = s.stats(recording.method(frame.method()));
                long total = exitNanos - frame.enterNanos();
                st.totalNanos += total;
                st.selfNanos += Math.max(0, total - frame.childNanos());
                onStack.get(thread).merge(frame.method(), -1, Integer::sum);
                if (how == CallStacks.Exit.EXCEPTION) {
                    String type = recording.className(exceptionClass);
                    st.exceptions.merge(type, 1L, Long::sum);
                    if (frame.pendingException() != exceptionClass) {
                        st.thrownHere.merge(type, 1L, Long::sum);
                    }
                }
            }

            @Override
            public void onCaught(long thread, CallStacks.Frame frame, int exceptionClass) {
                s.stats(recording.method(frame.method())).caught.merge(recording.className(exceptionClass), 1L,
                        Long::sum);
            }
        });
        recording.replay(stacks);
        s.flushPairs();
        s.unmatchedExits = stacks.unmatchedExits();
        s.unwoundFrames = stacks.unwoundFrames();
        return s;
    }

    private void addPair(long key) {
        pairs.computeIfAbsent(key, k -> new long[1])[0]++;
    }

    private void flushPairs() {
        pairs.forEach((key, count) -> graph.addRuntimeCall(recording.method((int) (key >>> 32)),
                recording.method((int) (long) key), count[0]));
        pairs.clear();
    }

    private MethodStats stats(MethodId m) {
        return stats.computeIfAbsent(m, k -> new MethodStats());
    }

    public Recording recording() {
        return recording;
    }

    /** Runtime call graph: an edge per observed caller → callee frame pair, with its count. */
    public CallGraph graph() {
        return graph;
    }

    /** Statistics of every method that ran. */
    public Map<MethodId, MethodStats> stats() {
        return Collections.unmodifiableMap(stats);
    }

    public long unmatchedExits() {
        return unmatchedExits;
    }

    public long unwoundFrames() {
        return unwoundFrames;
    }

    public int maxDepth() {
        return maxDepth;
    }

    /** {@code caller callee count}, one line per pair, ascending by count (the original summary). */
    public void writePairs(Writer out) {
        PrintWriter w = new PrintWriter(out);
        graph.edges().sorted(Comparator.comparingLong(Edge::runtimeCount)
                .thenComparing(Edge::caller).thenComparing(Edge::callee))
                .forEach(e -> w.println(e.caller().javaName() + " " + e.callee().javaName() + " " + e.runtimeCount()));
        w.flush();
    }

    /** Timing report, descending by self time (what {@code process_trace.rb} printed, plus more). */
    public void writeTiming(Writer out) {
        PrintWriter w = new PrintWriter(out);
        w.printf("%14s %14s %10s %6s  %s%n", "self(ns)", "total(ns)", "calls", "rec", "method");
        stats.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<MethodId, MethodStats> e) -> -e.getValue().selfNanos)
                        .thenComparing(Map.Entry::getKey))
                .forEach(e -> w.printf("%14d %14d %10d %6d  %s%n", e.getValue().selfNanos, e.getValue().totalNanos,
                        e.getValue().calls, e.getValue().maxRecursion, e.getKey().javaName()));
        if (unmatchedExits > 0 || unwoundFrames > 0) {
            w.printf("%n%d exits without entry (recording started mid-call), %d frames without exit event%n",
                    unmatchedExits, unwoundFrames);
        }
        w.flush();
    }
}
