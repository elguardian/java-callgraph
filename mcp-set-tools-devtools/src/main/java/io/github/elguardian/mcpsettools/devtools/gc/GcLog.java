package io.github.elguardian.mcpsettools.devtools.gc;

import java.util.List;

/**
 * A parsed GC log.
 *
 * @param collector {@code G1}, {@code Parallel}, {@code The Z Garbage Collector}, {@code ParallelGC (JDK 8)}...
 * @param warnings  to-space exhausted, evacuation failures, allocation stalls, humongous peaks
 */
public record GcLog(String source, String format, String collector, List<GcEvent> events, List<String> warnings) {

    public GcLog {
        events = List.copyOf(events);
        warnings = List.copyOf(warnings);
    }
}
