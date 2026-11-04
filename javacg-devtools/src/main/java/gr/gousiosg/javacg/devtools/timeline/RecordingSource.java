package gr.gousiosg.javacg.devtools.timeline;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import gr.gousiosg.javacg.analyze.MethodPattern;
import gr.gousiosg.javacg.analyze.Model;
import gr.gousiosg.javacg.core.MethodId;
import gr.gousiosg.javacg.core.trace.CallStacks;
import gr.gousiosg.javacg.core.trace.Recording;
import gr.gousiosg.javacg.core.trace.TraceSummary;

/**
 * Spans from javacg agent recordings: one per call of a method matching the pattern, and lock
 * spans when the recording has lock events ({@code locks=true}). The agent records a lock
 * before acquiring it, so the wait lasts until the thread's next event and the hold until the
 * release; with no event in between the two cannot be told apart and one {@code lock} span is
 * drawn. Several recordings share a time line through their clock anchor; each is a process.
 */
public final class RecordingSource {

    private RecordingSource() {
    }

    /**
     * @param methods      calls to draw, or null for every instrumented method
     * @param minMicros    shorter method calls are left out
     * @param maxSpans     stop collecting after this many spans
     */
    public static Timeline read(List<Path> recordings, MethodPattern methods, boolean locks, long minMicros, int maxSpans)
            throws IOException {
        List<Span> spans = new ArrayList<>();
        List<Mark> marks = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        List<TraceSummary> loaded = Model.loadRecordings(recordings);
        boolean many = loaded.size() > 1;
        for (TraceSummary t : loaded) {
            Recording rec = t.recording();
            String process = rec.label();
            boolean anchored = rec.toEpochMillis(0) != -1;
            if (many && !anchored) {
                notes.add("Recording " + rec.directory() + " has no clock anchor: its times are not comparable with the others");
            }
            Collector c = new Collector(rec, process, methods, locks, minMicros, maxSpans, spans, marks);
            rec.replay(new CallStacks(c));
            if (c.dropped > 0) {
                notes.add(c.dropped + " spans of " + process + " not collected (max " + maxSpans + ")");
            }
            if (locks && c.lockEvents > 0) {
                notes.add("Lock waits are upper bounds: the agent records a lock before acquiring it and the acquisition "
                        + "is taken from the thread's next event");
            }
            if (!rec.isComplete()) {
                notes.add("Recording " + rec.directory() + " was not stopped cleanly: calls still open are cut at their last event");
            }
        }
        return new Timeline(spans, marks, notes);
    }

    private static final class Collector implements CallStacks.Listener {
        private final Recording rec;
        private final String process;
        private final MethodPattern methods;
        private final boolean locks;
        private final long minMicros;
        private final int maxSpans;
        private final List<Span> spans;
        private final List<Mark> marks;
        private final Map<Integer, Boolean> shown = new HashMap<>();
        private final Map<Long, Integer> depth = new HashMap<>();
        private final Map<Long, Deque<Boolean>> counted = new HashMap<>();
        /** Thread → lock requested but not yet seen acquired: {hash, class, nanos}. */
        private final Map<Long, long[]> waiting = new HashMap<>();
        /** Thread → holds: {hash, class, acquiredNanos} */
        private final Map<Long, Deque<long[]>> holds = new HashMap<>();
        int dropped;
        long lockEvents;

        Collector(Recording rec, String process, MethodPattern methods, boolean locks, long minMicros, int maxSpans,
                List<Span> spans, List<Mark> marks) {
            this.rec = rec;
            this.process = process;
            this.methods = methods;
            this.locks = locks;
            this.minMicros = minMicros;
            this.maxSpans = maxSpans;
            this.spans = spans;
            this.marks = marks;
        }

        private boolean shown(int method) {
            return shown.computeIfAbsent(method, m -> methods == null || methods.matches(rec.method(m)));
        }

        @Override
        public void onEnter(long thread, CallStacks.Frame frame, List<CallStacks.Frame> stack) {
            acquired(thread, frame.enterNanos());
            boolean show = shown(frame.method());
            counted.computeIfAbsent(thread, k -> new ArrayDeque<>()).push(show);
            if (show) {
                depth.merge(thread, 1, Integer::sum);
            }
        }

        @Override
        public void onExit(long thread, CallStacks.Frame frame, List<CallStacks.Frame> stack, CallStacks.Exit how,
                long exitNanos, int exceptionClass) {
            acquired(thread, exitNanos);
            Deque<Boolean> c = counted.get(thread);
            boolean show = c != null && !c.isEmpty() ? c.pop() : shown(frame.method());
            if (!show) {
                return;
            }
            int d = depth.merge(thread, -1, Integer::sum);
            long start = micros(frame.enterNanos());
            long end = micros(exitNanos);
            if (end - start < minMicros) {
                return;
            }
            MethodId m = rec.method(frame.method());
            Map<String, String> args = new HashMap<>();
            args.put("method", m.javaName());
            if (how == CallStacks.Exit.EXCEPTION) {
                args.put("threw", rec.className(exceptionClass).replace('/', '.'));
            } else if (how == CallStacks.Exit.OPEN) {
                args.put("open", "still running when the recording ended");
            }
            if (frame.args() != null) {
                args.put("args", frame.args());
            }
            add(new Span(process, rec.threadName(thread), start, end, m.className().substring(m.className().lastIndexOf('.') + 1)
                    + "." + m.name(), "method", Math.max(0, d), args));
            if (how == CallStacks.Exit.EXCEPTION && (exceptionClass != frame.pendingException() || stack.isEmpty())) {
                marks.add(new Mark(process, rec.threadName(thread), end, "throws " + rec.className(exceptionClass)
                        .replace('/', '.'), "exception"));
            }
        }

        @Override
        public void onLock(long thread, List<CallStacks.Frame> stack, int identityHash, int lockClass, long nanos,
                boolean acquire) {
            if (!locks) {
                return;
            }
            lockEvents++;
            long[] w = waiting.remove(thread);
            String threadName = rec.threadName(thread);
            if (w != null) {
                if (!acquire && w[0] == identityHash) {
                    // nothing happened between request and release: wait and hold are one block
                    add(new Span(process, threadName, micros(w[2]), micros(nanos), "lock " + lockName((int) w[1], (int) w[0]),
                            "lock", depth.getOrDefault(thread, 0), Map.of()));
                    return;
                }
                hold(thread, w, nanos);
            }
            if (acquire) {
                waiting.put(thread, new long[] { identityHash, lockClass, nanos });
            } else {
                Deque<long[]> h = holds.get(thread);
                if (h == null) {
                    return;
                }
                for (var it = h.iterator(); it.hasNext();) {
                    long[] held = it.next();
                    if (held[0] == identityHash) {
                        it.remove();
                        add(new Span(process, threadName, micros(held[2]), micros(nanos),
                                "hold " + lockName((int) held[1], (int) held[0]), "lock-hold", depth.getOrDefault(thread, 0),
                                Map.of()));
                        break;
                    }
                }
            }
        }

        /** The first event after a lock request: the lock is held from now. */
        private void acquired(long thread, long nanos) {
            long[] w = waiting.remove(thread);
            if (w != null) {
                hold(thread, w, nanos);
            }
        }

        private void hold(long thread, long[] w, long nanos) {
            if (nanos > w[2]) {
                add(new Span(process, rec.threadName(thread), micros(w[2]), micros(nanos),
                        "wait " + lockName((int) w[1], (int) w[0]), "lock-wait", depth.getOrDefault(thread, 0), Map.of()));
            }
            holds.computeIfAbsent(thread, k -> new ArrayDeque<>()).push(new long[] { w[0], w[1], nanos });
        }

        private String lockName(int lockClass, int hash) {
            String cls = rec.className(lockClass).replace('/', '.');
            return cls.substring(cls.lastIndexOf('.') + 1) + "@" + Integer.toHexString(hash);
        }

        private void add(Span s) {
            if (spans.size() >= maxSpans) {
                dropped++;
            } else {
                spans.add(s);
            }
        }

        /** Epoch microseconds through the clock anchor, or plain nanoTime microseconds without one. */
        private long micros(long nanos) {
            double ms = rec.toEpochMillis(nanos);
            return ms < 0 ? nanos / 1000 : Math.round(ms * 1000);
        }
    }
}
