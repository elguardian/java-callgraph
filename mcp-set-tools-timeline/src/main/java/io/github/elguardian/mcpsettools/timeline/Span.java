package io.github.elguardian.mcpsettools.timeline;

import java.util.Map;

/**
 * A block of time on one thread.
 *
 * @param process  the JVM or source the thread belongs to (one lane group per process)
 * @param start    epoch microseconds (1970-01-01 based for time-only sources)
 * @param category {@code method}, {@code lock-wait}, {@code lock-hold}, {@code lock}, {@code span},
 *                 {@code module}, ...
 * @param depth    nesting among the spans shown on this thread (0: outermost)
 */
public record Span(String process, String thread, long start, long end, String label, String category, int depth,
        Map<String, String> args) {

    public Span {
        args = args == null ? Map.of() : Map.copyOf(args);
        if (end < start) {
            end = start;
        }
    }

    public long duration() {
        return end - start;
    }

    public String lane() {
        return process + " / " + thread;
    }

    public boolean overlaps(Span o) {
        return start < o.end && o.start < end;
    }
}
