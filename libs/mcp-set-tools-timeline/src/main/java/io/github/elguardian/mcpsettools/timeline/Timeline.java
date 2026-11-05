package io.github.elguardian.mcpsettools.timeline;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/** Spans and marks on threads, from any source; the input of the analysis and the renderers. */
public final class Timeline {

    private final List<Span> spans;
    private final List<Mark> marks;
    private final List<String> notes;

    public Timeline(List<Span> spans, List<Mark> marks, List<String> notes) {
        this.spans = spans.stream().sorted(Comparator.comparingLong(Span::start).thenComparingInt(Span::depth)).toList();
        this.marks = marks.stream().sorted(Comparator.comparingLong(Mark::time)).toList();
        this.notes = List.copyOf(notes);
    }

    public List<Span> spans() {
        return spans;
    }

    public List<Mark> marks() {
        return marks;
    }

    /** Remarks about how the timeline was built (dropped spans, approximations). */
    public List<String> notes() {
        return notes;
    }

    public boolean isEmpty() {
        return spans.isEmpty() && marks.isEmpty();
    }

    public long start() {
        long s = Long.MAX_VALUE;
        for (Span x : spans) {
            s = Math.min(s, x.start());
        }
        for (Mark m : marks) {
            s = Math.min(s, m.time());
        }
        return s == Long.MAX_VALUE ? 0 : s;
    }

    public long end() {
        long e = Long.MIN_VALUE;
        for (Span x : spans) {
            e = Math.max(e, x.end());
        }
        for (Mark m : marks) {
            e = Math.max(e, m.time());
        }
        return e == Long.MIN_VALUE ? 0 : e;
    }

    /** Lanes ({@code process / thread}) in order of first activity. */
    public List<String> lanes() {
        Set<String> lanes = new LinkedHashSet<>();
        List<Object[]> firsts = new ArrayList<>();
        spans.forEach(s -> firsts.add(new Object[] { s.start(), s.lane() }));
        marks.forEach(m -> firsts.add(new Object[] { m.time(), m.lane() }));
        firsts.sort(Comparator.comparingLong(o -> (Long) o[0]));
        firsts.forEach(o -> lanes.add((String) o[1]));
        return new ArrayList<>(lanes);
    }

    /** True when the spans and marks come from more than one process (JVM, log file). */
    public boolean multiProcess() {
        Set<String> processes = new LinkedHashSet<>();
        spans.forEach(x -> processes.add(x.process()));
        marks.forEach(x -> processes.add(x.process()));
        return processes.size() > 1;
    }

    /** The spans and marks that overlap {@code [from, to]} and pass the filters, clipped to the window. */
    public Timeline filter(Long from, Long to, Predicate<String> thread, Predicate<String> label) {
        long f = from == null ? Long.MIN_VALUE : from;
        long t = to == null ? Long.MAX_VALUE : to;
        List<Span> s = spans.stream().filter(x -> x.end() >= f && x.start() <= t)
                .filter(x -> thread.test(x.thread()) && label.test(x.label()))
                .map(x -> x.start() >= f && x.end() <= t ? x
                        : new Span(x.process(), x.thread(), Math.max(f, x.start()), Math.min(t, x.end()), x.label(),
                                x.category(), x.depth(), x.args()))
                .toList();
        List<Mark> m = marks.stream().filter(x -> x.time() >= f && x.time() <= t)
                .filter(x -> thread.test(x.thread()) && label.test(x.label())).toList();
        return new Timeline(s, m, notes);
    }

    /**
     * At most {@code max} spans: the longest ones are kept, so the shape of a busy timeline
     * survives in a text diagram. Marks are kept.
     */
    public Timeline limit(int max) {
        if (spans.size() <= max) {
            return this;
        }
        List<Span> kept = spans.stream().sorted(Comparator.comparingLong(Span::duration).reversed()).limit(max).toList();
        List<String> n = new ArrayList<>(notes);
        n.add("Showing the " + max + " longest of " + spans.size() + " spans (max_spans)");
        return new Timeline(kept, marks, n);
    }
}
