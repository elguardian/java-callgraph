package io.github.elguardian.mcpsettools.jvm.gc;

/**
 * One collection or pause.
 *
 * @param id       the {@code GC(n)} number, or -1 (JDK 8 logs)
 * @param uptime   seconds since JVM start, or -1
 * @param wall     wall-clock time in epoch microseconds, or Long.MIN_VALUE
 * @param kind     {@code Pause Young}, {@code Pause Full}, {@code Pause Remark}, {@code Concurrent Mark Cycle},
 *                 {@code Major Collection} (ZGC)...
 * @param detail   the parenthesized parts: {@code Normal}, {@code Mixed}, {@code G1 Evacuation Pause},
 *                 {@code System.gc()}, {@code Allocation Failure}...
 * @param pause    true for stop-the-world pauses, false for concurrent cycles
 * @param before   heap used before, KB, or -1
 * @param after    heap used after, KB, or -1
 * @param capacity heap capacity after, KB, or -1
 * @param millis   duration
 */
public record GcEvent(long id, double uptime, long wall, String kind, String detail, boolean pause, long before, long after,
        long capacity, double millis) {

    public boolean full() {
        return kind.contains("Full");
    }

    /** Seconds on the log's time line: uptime, or wall clock when the log has no uptime. */
    public double time() {
        return uptime >= 0 ? uptime : wall == Long.MIN_VALUE ? -1 : wall / 1e6;
    }
}
