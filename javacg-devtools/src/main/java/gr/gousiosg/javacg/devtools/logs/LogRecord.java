package gr.gousiosg.javacg.devtools.logs;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import gr.gousiosg.javacg.commons.stack.StackTrace;
import gr.gousiosg.javacg.commons.stack.StackTraceParser;

/**
 * One log record: the line matching the format and the continuation lines that follow it
 * (multi-line messages, stack traces).
 *
 * @param line      1-based line number of the first line
 * @param micros    timestamp in epoch microseconds, or {@link #NO_TIME}
 * @param level     normalized level (TRACE, DEBUG, INFO, WARN, ERROR, FATAL), or null
 * @param rawLevel  the level as written
 * @param fields    other named groups of the format (class, method, mdc_*, ...)
 */
public record LogRecord(Path file, long line, long micros, String timestamp, String level, String rawLevel,
        String thread, String logger, String message, Map<String, String> fields, List<String> continuation) {

    public static final long NO_TIME = Long.MIN_VALUE;

    public LogRecord {
        fields = Map.copyOf(fields);
        continuation = List.copyOf(continuation);
    }

    public boolean hasTime() {
        return micros != NO_TIME;
    }

    public Instant instant() {
        return hasTime() ? Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000), Math.floorMod(micros, 1_000_000) * 1000L)
                : null;
    }

    /** The message with its continuation lines. */
    public String fullMessage() {
        return continuation.isEmpty() ? message : message + "\n" + String.join("\n", continuation);
    }

    /** The first stack trace in the message or continuation lines, or null. */
    public StackTrace stackTrace() {
        if (continuation.isEmpty() && !message.contains("\tat ")) {
            return null;
        }
        List<StackTrace> all = StackTraceParser.parseAll(fullMessage());
        return all.isEmpty() ? null : all.get(0);
    }

    /** Severity order: TRACE 0 ... FATAL 5; unknown -1. */
    public int severity() {
        return Levels.rank(level);
    }
}
