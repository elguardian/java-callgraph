package io.github.elguardian.mcpsettools.logs;

import io.github.elguardian.mcpsettools.commons.text.Templates;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Pattern;

import io.github.elguardian.mcpsettools.commons.stack.StackTrace;
import io.github.elguardian.mcpsettools.commons.stack.StackTraces;

/** One pass over log files: filtering, records with context, counts, groups, error groups, histogram. */
public final class LogQuery {

    public enum GroupBy {
        LEVEL, LOGGER, THREAD, TEMPLATE
    }

    /** A time bound: an instant, or a time of day for logs whose timestamps have no date. */
    public record TimeBound(long micros, LocalTime timeOfDay) {

        /** ISO date-time ({@code 2024-05-01T10:00:00}, with or without offset, space allowed) or time ({@code 10:00:00}). */
        public static TimeBound parse(String s, ZoneId zone) {
            String t = s.strip().replace(' ', 'T');
            try {
                if (!t.contains("T")) {
                    return new TimeBound(LogRecord.NO_TIME, LocalTime.parse(t.replace(',', '.')));
                }
                try {
                    var i = OffsetDateTime.parse(t).toInstant();
                    return new TimeBound(i.getEpochSecond() * 1_000_000 + i.getNano() / 1000, null);
                } catch (DateTimeParseException e) {
                    var i = LocalDateTime.parse(t.replace(',', '.')).atZone(zone).toInstant();
                    return new TimeBound(i.getEpochSecond() * 1_000_000 + i.getNano() / 1000, null);
                }
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("Cannot read time '" + s + "': use 2024-05-01T10:00:00 or 10:00:00");
            }
        }

        int compare(LogRecord r, ZoneId zone) {
            if (timeOfDay != null) {
                LocalTime rt = r.instant().atZone(zone).toLocalTime();
                return rt.compareTo(timeOfDay);
            }
            return Long.compare(r.micros(), micros);
        }
    }

    /** Which records match. Null fields do not filter. */
    public record Filter(String minLevel, Set<String> levels, Pattern thread, Pattern logger, Pattern message,
            boolean searchContinuation, TimeBound from, TimeBound to, boolean withStackTrace, ZoneId zone) {

        public static Filter all() {
            return new Filter(null, Set.of(), null, null, null, true, null, null, false, ZoneId.systemDefault());
        }

        boolean test(LogRecord r) {
            if (minLevel != null && r.severity() < Levels.rank(minLevel)) {
                return false;
            }
            if (!levels.isEmpty() && (r.level() == null || !levels.contains(r.level()))) {
                return false;
            }
            if (thread != null && (r.thread() == null || !thread.matcher(r.thread()).find())) {
                return false;
            }
            if (logger != null && (r.logger() == null || !logger.matcher(r.logger()).find())) {
                return false;
            }
            if (message != null && !message.matcher(searchContinuation ? r.fullMessage() : r.message()).find()) {
                return false;
            }
            if ((from != null || to != null) && !r.hasTime()) {
                return false;
            }
            if (from != null && from.compare(r, zone) < 0) {
                return false;
            }
            if (to != null && to.compare(r, zone) > 0) {
                return false;
            }
            return !withStackTrace || r.stackTrace() != null;
        }
    }

    /** What to collect. */
    public record Options(int limit, boolean tail, int context, GroupBy groupBy, boolean errors, long bucketMicros,
            List<String> appPackages) {
    }

    public record Group(String key, long count, long first, long last, String example, Map<String, Long> levels) {
    }

    public record ErrorGroup(String signature, String id, long count, long first, long last, LogRecord example,
            Set<String> threads) {
    }

    /** A record in the output; context records surround matches. */
    public record Hit(LogRecord record, boolean context) {
    }

    public static final class Result {
        long scanned;
        long matched;
        int shownMatches;
        long firstMicros = LogRecord.NO_TIME;
        long lastMicros = LogRecord.NO_TIME;
        final Map<String, Long> byLevel = new LinkedHashMap<>();
        final List<Hit> hits = new ArrayList<>();
        boolean truncated;
        List<Group> groups = List.of();
        List<ErrorGroup> errors = List.of();
        final TreeMap<Long, Map<String, Long>> histogram = new TreeMap<>();

        public long scanned() {
            return scanned;
        }

        public long matched() {
            return matched;
        }

        public long firstMicros() {
            return firstMicros;
        }

        public long lastMicros() {
            return lastMicros;
        }

        public Map<String, Long> byLevel() {
            return byLevel;
        }

        public List<Hit> hits() {
            return hits;
        }

        public boolean truncated() {
            return truncated;
        }

        public List<Group> groups() {
            return groups;
        }

        public List<ErrorGroup> errors() {
            return errors;
        }

        /** Bucket start (epoch µs) → count per level ({@code ""} for records without a level). */
        public TreeMap<Long, Map<String, Long>> histogram() {
            return histogram;
        }
    }

    private LogQuery() {
    }

    public static Result run(List<Path> files, LogFormat format, Filter filter, Options opts) throws IOException {
        Result res = new Result();
        Deque<LogRecord> before = new ArrayDeque<>();
        int[] after = { 0 };
        Map<String, GroupAcc> groups = new LinkedHashMap<>();
        Map<String, ErrorAcc> errors = new LinkedHashMap<>();
        Function<LogRecord, String> key = opts.groupBy() == null ? null : switch (opts.groupBy()) {
            case LEVEL -> r -> String.valueOf(r.level());
            case LOGGER -> r -> String.valueOf(r.logger());
            case THREAD -> r -> String.valueOf(r.thread());
            case TEMPLATE -> r -> Templates.of(r.message());
        };
        LogReader.read(files, format, r -> {
            res.scanned++;
            if (!filter.test(r)) {
                if (after[0] > 0 && !opts.tail()) {
                    addHit(res, new Hit(r, true));
                    after[0]--;
                } else if (opts.context() > 0) {
                    before.addLast(r);
                    if (before.size() > opts.context()) {
                        before.removeFirst();
                    }
                }
                return;
            }
            res.matched++;
            if (r.hasTime()) {
                if (res.firstMicros == LogRecord.NO_TIME || r.micros() < res.firstMicros) {
                    res.firstMicros = r.micros();
                }
                if (r.micros() > res.lastMicros) {
                    res.lastMicros = r.micros();
                }
            }
            res.byLevel.merge(r.level() == null ? "" : r.level(), 1L, Long::sum);
            if (opts.tail()) {
                res.hits.add(new Hit(r, false));
                if (res.hits.size() > opts.limit()) {
                    res.hits.remove(0);
                    res.truncated = true;
                }
            } else if (res.shownMatches >= opts.limit()) {
                res.truncated = true;
                before.clear();
                after[0] = 0;
            } else {
                before.forEach(b -> addHit(res, new Hit(b, true)));
                before.clear();
                addHit(res, new Hit(r, false));
                res.shownMatches++;
                after[0] = opts.context();
            }
            if (key != null) {
                groups.computeIfAbsent(key.apply(r), k -> new GroupAcc(r)).add(r);
            }
            if (opts.errors() && (r.severity() >= Levels.rank("ERROR") || r.stackTrace() != null)) {
                StackTrace t = r.stackTrace();
                String sig = t != null ? StackTraces.signature(t, opts.appPackages(), 3)
                        : r.level() + " " + r.logger() + ": " + Templates.of(r.message());
                errors.computeIfAbsent(sig, k -> new ErrorAcc(r)).add(r);
            }
            if (opts.bucketMicros() > 0 && r.hasTime()) {
                long bucket = Math.floorDiv(r.micros(), opts.bucketMicros()) * opts.bucketMicros();
                res.histogram.computeIfAbsent(bucket, b -> new TreeMap<>()).merge(r.level() == null ? "" : r.level(), 1L,
                        Long::sum);
            }
        });
        res.groups = groups.entrySet().stream().map(e -> e.getValue().group(e.getKey()))
                .sorted(Comparator.comparingLong(Group::count).reversed()).toList();
        res.errors = errors.entrySet().stream().map(e -> e.getValue().group(e.getKey()))
                .sorted(Comparator.comparingLong(ErrorGroup::count).reversed()).toList();
        return res;
    }

    private static void addHit(Result res, Hit h) {
        if (res.hits.isEmpty() || res.hits.get(res.hits.size() - 1).record() != h.record()) {
            res.hits.add(h);
        }
    }

    private static final class GroupAcc {
        final LogRecord example;
        long count;
        long first = LogRecord.NO_TIME;
        long last = LogRecord.NO_TIME;
        final Map<String, Long> levels = new LinkedHashMap<>();

        GroupAcc(LogRecord example) {
            this.example = example;
        }

        void add(LogRecord r) {
            count++;
            if (r.hasTime()) {
                first = first == LogRecord.NO_TIME ? r.micros() : Math.min(first, r.micros());
                last = Math.max(last, r.micros());
            }
            levels.merge(r.level() == null ? "" : r.level(), 1L, Long::sum);
        }

        Group group(String key) {
            return new Group(key, count, first, last, example.message(), levels);
        }
    }

    private static final class ErrorAcc {
        final LogRecord example;
        long count;
        long first = LogRecord.NO_TIME;
        long last = LogRecord.NO_TIME;
        final Set<String> threads = new LinkedHashSet<>();

        ErrorAcc(LogRecord example) {
            this.example = example;
        }

        void add(LogRecord r) {
            count++;
            if (r.hasTime()) {
                first = first == LogRecord.NO_TIME ? r.micros() : Math.min(first, r.micros());
                last = Math.max(last, r.micros());
            }
            if (r.thread() != null && threads.size() < 5) {
                threads.add(r.thread());
            }
        }

        ErrorGroup group(String signature) {
            return new ErrorGroup(signature, StackTraces.signatureId(signature), count, first, last, example, threads);
        }
    }
}
