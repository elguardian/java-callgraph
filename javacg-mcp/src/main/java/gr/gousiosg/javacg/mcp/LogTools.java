package gr.gousiosg.javacg.mcp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import gr.gousiosg.javacg.devtools.logs.Levels;
import gr.gousiosg.javacg.devtools.logs.LogFormat;
import gr.gousiosg.javacg.devtools.logs.LogQuery;
import gr.gousiosg.javacg.devtools.logs.LogReader;
import gr.gousiosg.javacg.devtools.logs.LogRecord;
import gr.gousiosg.javacg.devtools.stack.StackTrace;
import gr.gousiosg.javacg.devtools.stack.StackTraces;

/** Log files parsed by layout pattern: search, counts, groups, error groups, histograms. */
final class LogTools {

    static final String PATTERN_HELP = "Log format: 'auto' (detect from the first lines), a preset ("
            + String.join(", ", LogFormat.PRESETS.keySet()) + "), a log4j2/logback/JBoss layout such as "
            + "'%d{yyyy-MM-dd HH:mm:ss,SSS} %-5p [%c] (%t) %s%e%n', or 'regex:' followed by a Java regex with named "
            + "groups ts, level, thread, logger, message";

    private enum Output {
        RECORDS, COUNT, GROUPS, ERRORS, HISTOGRAM
    }

    private enum Stacks {
        NONE, ONELINE, TRIMMED, FULL
    }

    private static final Pattern DURATION = Pattern.compile("(\\d+)\\s*(ms|s|m|h|d)");

    List<ToolDef> tools() {
        return List.of(query(), pattern());
    }

    private ToolDef query() {
        return new ToolDef("log_query", "Query log files", """
                Reads log files (plain or .gz) by their layout pattern, keeping multi-line messages and \
                stack traces with their record, and answers one question per call: records (matching \
                records, optionally with context records around them, or the last ones with tail), count \
                (matches per level), groups (counts by level, logger, thread or message template, where \
                numbers, ids and quoted values are replaced by <*>), errors (ERROR+ records and records \
                with stack traces grouped by exception signature, with first/last time and threads) or \
                histogram (matches per time bucket and level). Filters combine: min_level, levels, \
                thread/logger/message regexes, from/to times, with_stack_trace.""", true,
                Schema.object().strings("files", "Log files", true)
                        .string("pattern", PATTERN_HELP + ". Default auto", false)
                        .string("date_format", "Timestamp format overriding the pattern's, e.g. 'dd/MM/yyyy HH:mm:ss'",
                                false)
                        .string("zone", "Time zone of timestamps without one (default: the server's)", false)
                        .choice("output", "What to return", "records", "records", "count", "groups", "errors",
                                "histogram")
                        .optionalChoice("min_level", "Lowest level", "TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL")
                        .strings("levels", "Only these levels", false)
                        .string("thread", "Regex searched in the thread name", false)
                        .string("logger", "Regex searched in the logger name", false)
                        .string("message", "Regex searched in the message (and, with search_continuation, its "
                                + "continuation lines and stack trace)", false)
                        .bool("search_continuation", "Message regex also searches continuation lines", true)
                        .bool("with_stack_trace", "Only records carrying a stack trace", false)
                        .string("from", "Start time: 2024-05-01T10:00:00 (offset optional) or 10:00:00", false)
                        .string("to", "End time, same forms as from", false)
                        .choice("group_by", "Grouping for output=groups", "template", "template", "level", "logger",
                                "thread")
                        .string("bucket", "Histogram bucket: 10s, 1m, 1h... (default: about 30 buckets)", false)
                        .integer("limit", "Maximum records or groups returned", 50)
                        .bool("tail", "Return the last matching records instead of the first", false)
                        .integer("context", "Records shown before and after each match", 0)
                        .choice("stack_traces", "How records show stack traces", "oneline", "none", "oneline",
                                "trimmed", "full")
                        .integer("max_message", "Longest message returned, in characters", 500)
                        .strings("app_packages", "Application package prefixes, for stack trace signatures", false)
                        .tableFormat().build(),
                args -> {
                    List<Path> files = files(args);
                    ZoneId zone = zone(args);
                    LogFormat format = format(args, files.get(0), zone);
                    Output output = args.choice("output", Output.class, Output.RECORDS);
                    LogQuery.GroupBy groupBy = output == Output.GROUPS
                            ? args.choice("group_by", LogQuery.GroupBy.class, LogQuery.GroupBy.TEMPLATE) : null;
                    int limit = args.integer("limit", 50);
                    long bucket = output == Output.HISTOGRAM ? bucket(args, files, format, filter(args, zone)) : 0;
                    LogQuery.Options opts = new LogQuery.Options(output == Output.RECORDS ? limit : 0,
                            args.bool("tail", false), output == Output.RECORDS ? args.integer("context", 0) : 0, groupBy,
                            output == Output.ERRORS, bucket, args.strings("app_packages"));
                    LogQuery.Result r = LogQuery.run(files, format, filter(args, zone), opts);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("format", format.name());
                    m.put("scanned", r.scanned());
                    m.put("matched", r.matched());
                    if (r.firstMicros() != LogRecord.NO_TIME) {
                        m.put("first", time(r.firstMicros(), zone));
                        m.put("last", time(r.lastMicros(), zone));
                    }
                    m.put("by_level", r.byLevel());
                    List<Map<String, Object>> rows = new ArrayList<>();
                    List<String> columns = null;
                    switch (output) {
                        case RECORDS -> {
                            boolean several = files.size() > 1;
                            r.hits().forEach(h -> rows.add(record(h, args, several, zone)));
                            m.put("records", rows);
                            if (r.truncated()) {
                                m.put("truncated", true);
                            }
                            columns = List.of("time", "level", "thread", "logger", "message");
                        }
                        case GROUPS -> {
                            r.groups().stream().limit(limit).forEach(g -> {
                                Map<String, Object> row = new LinkedHashMap<>();
                                row.put(Views.lower(groupBy), g.key());
                                row.put("count", g.count());
                                if (g.first() != LogRecord.NO_TIME) {
                                    row.put("first", time(g.first(), zone));
                                    row.put("last", time(g.last(), zone));
                                }
                                row.put("levels", g.levels());
                                if (groupBy == LogQuery.GroupBy.TEMPLATE) {
                                    row.put("example", cut(g.example(), args.integer("max_message", 500)));
                                }
                                rows.add(row);
                            });
                            m.put("groups", rows);
                            m.put("distinct", r.groups().size());
                            columns = List.of(Views.lower(groupBy), "count", "first", "last");
                        }
                        case ERRORS -> {
                            List<String> app = args.strings("app_packages");
                            r.errors().stream().limit(limit).forEach(e -> {
                                Map<String, Object> row = new LinkedHashMap<>();
                                row.put("signature_id", e.id());
                                row.put("count", e.count());
                                row.put("signature", e.signature());
                                if (e.first() != LogRecord.NO_TIME) {
                                    row.put("first", time(e.first(), zone));
                                    row.put("last", time(e.last(), zone));
                                }
                                row.put("threads", List.copyOf(e.threads()));
                                LogRecord ex = e.example();
                                row.put("example", location(ex, several(files)) + " " + cut(ex.message(), 300));
                                StackTrace t = ex.stackTrace();
                                if (t != null) {
                                    row.put("stack_trace", StackTraces.render(t, true, app));
                                }
                                rows.add(row);
                            });
                            m.put("errors", rows);
                            m.put("distinct", r.errors().size());
                            columns = List.of("count", "signature", "first", "last");
                        }
                        case HISTOGRAM -> {
                            m.put("bucket_seconds", bucket / 1_000_000.0);
                            r.histogram().forEach((start, levels) -> {
                                Map<String, Object> row = new LinkedHashMap<>();
                                row.put("start", time(start, zone));
                                row.put("total", levels.values().stream().mapToLong(Long::longValue).sum());
                                for (String l : Levels.ORDER) {
                                    if (r.byLevel().containsKey(l)) {
                                        row.put(l, levels.getOrDefault(l, 0L));
                                    }
                                }
                                rows.add(row);
                            });
                            m.put("histogram", rows);
                        }
                        case COUNT -> {
                        }
                    }
                    Views.table(m, rows, columns, args);
                    return m;
                });
    }

    private ToolDef pattern() {
        return new ToolDef("log_pattern", "Check a log format", """
                Shows the built-in log formats, the regex a layout pattern compiles to, and how the first \
                records of a file parse with it (or which preset 'auto' detects), to check a pattern before \
                querying. Reports how many of the first lines start a record.""", true,
                Schema.object().string("pattern", PATTERN_HELP + ". Default auto when a file is given", false)
                        .string("file", "Log file to try the pattern on", false)
                        .string("date_format", "Timestamp format overriding the pattern's", false)
                        .string("zone", "Time zone of timestamps without one", false)
                        .integer("records", "How many parsed records to show", 5).build(),
                args -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    ZoneId zone = zone(args);
                    if (!args.has("file") && !args.has("pattern")) {
                        m.put("presets", LogFormat.PRESETS);
                        return m;
                    }
                    Path file = args.has("file") ? ToolArgs.path(args.require("file")) : null;
                    LogFormat format = file == null ? compile(args.require("pattern"), args, zone) : format(args, file, zone);
                    m.put("format", format.name());
                    m.put("spec", LogFormat.PRESETS.getOrDefault(format.spec(), format.spec()));
                    m.put("regex", format.regex());
                    if (file != null) {
                        List<String> head = LogReader.head(file, 200);
                        long starts = head.stream().filter(l -> format.parseLine(l, file, 0).isPresent()).count();
                        m.put("lines_checked", head.size());
                        m.put("record_starts", starts);
                        List<Map<String, Object>> sample = new ArrayList<>();
                        int n = args.integer("records", 5);
                        try {
                            LogReader.read(List.of(file), format, r -> {
                                if (sample.size() >= n) {
                                    throw new StopReading();
                                }
                                Map<String, Object> row = new LinkedHashMap<>();
                                row.put("line", r.line());
                                row.put("timestamp", r.timestamp());
                                row.put("parsed_time", r.hasTime() ? time(r.micros(), zone) : null);
                                row.put("level", r.rawLevel());
                                row.put("thread", r.thread());
                                row.put("logger", r.logger());
                                row.put("message", cut(r.message(), 200));
                                if (!r.fields().isEmpty()) {
                                    row.put("fields", r.fields());
                                }
                                row.put("continuation_lines", r.continuation().size());
                                sample.add(row);
                            });
                        } catch (StopReading e) {
                            // enough records
                        }
                        m.put("sample", sample);
                    }
                    return m;
                });
    }

    private static final class StopReading extends RuntimeException {
        private static final long serialVersionUID = 1L;

        StopReading() {
            super(null, null, false, false);
        }
    }

    private static List<Path> files(ToolArgs args) {
        List<Path> files = args.paths("files");
        if (files.isEmpty()) {
            throw new ToolException("Give 'files'");
        }
        for (Path f : files) {
            if (!Files.isRegularFile(f)) {
                throw new ToolException("Not a file: " + f);
            }
        }
        return files;
    }

    private static LogFormat format(ToolArgs args, Path first, ZoneId zone) throws IOException {
        String spec = args.string("pattern", "auto");
        if (!spec.equals("auto")) {
            return compile(spec, args, zone);
        }
        LogFormat detected = LogFormat.detect(LogReader.head(first, 200)).orElseThrow(() -> new ToolException(
                "Could not recognize the log format of " + first + ": pass 'pattern'. " + PATTERN_HELP));
        return args.has("date_format") ? LogFormat.of(detected.name(), args.require("date_format"), zone.getId())
                : LogFormat.of(detected.name(), null, zone.getId());
    }

    private static LogFormat compile(String spec, ToolArgs args, ZoneId zone) {
        try {
            return LogFormat.of(spec, args.string("date_format", null), zone.getId());
        } catch (IllegalArgumentException e) {
            throw new ToolException(e.getMessage());
        }
    }

    private static ZoneId zone(ToolArgs args) {
        try {
            return args.has("zone") ? ZoneId.of(args.require("zone")) : ZoneId.systemDefault();
        } catch (java.time.DateTimeException e) {
            throw new ToolException("Unknown zone " + args.string("zone", null));
        }
    }

    private static LogQuery.Filter filter(ToolArgs args, ZoneId zone) {
        Set<String> levels = new HashSet<>();
        args.strings("levels").forEach(l -> levels.add(Levels.normalize(l)));
        try {
            return new LogQuery.Filter(args.has("min_level") ? Levels.normalize(args.require("min_level")) : null, levels,
                    regex(args, "thread"), regex(args, "logger"), regex(args, "message"),
                    args.bool("search_continuation", true),
                    args.has("from") ? LogQuery.TimeBound.parse(args.require("from"), zone) : null,
                    args.has("to") ? LogQuery.TimeBound.parse(args.require("to"), zone) : null,
                    args.bool("with_stack_trace", false), zone);
        } catch (IllegalArgumentException e) {
            throw new ToolException(e.getMessage());
        }
    }

    private static Pattern regex(ToolArgs args, String name) {
        if (!args.has(name)) {
            return null;
        }
        try {
            return Pattern.compile(args.require(name));
        } catch (PatternSyntaxException e) {
            throw new ToolException("'" + name + "' is not a valid regex: " + e.getDescription());
        }
    }

    /** The bucket argument, or a round size giving about 30 buckets over the matched time span. */
    private static long bucket(ToolArgs args, List<Path> files, LogFormat format, LogQuery.Filter filter)
            throws IOException {
        if (args.has("bucket")) {
            Matcher m = DURATION.matcher(args.require("bucket"));
            if (!m.matches()) {
                throw new ToolException("'bucket' must look like 500ms, 10s, 5m, 1h or 1d");
            }
            long n = Long.parseLong(m.group(1));
            long micros = n * switch (m.group(2)) {
                case "ms" -> 1_000L;
                case "s" -> 1_000_000L;
                case "m" -> 60_000_000L;
                case "h" -> 3_600_000_000L;
                default -> 86_400_000_000L;
            };
            if (micros <= 0) {
                throw new ToolException("'bucket' must be positive");
            }
            return micros;
        }
        LogQuery.Result span = LogQuery.run(files, format, filter,
                new LogQuery.Options(0, false, 0, null, false, 0, List.of()));
        long range = Math.max(1, span.lastMicros() - span.firstMicros());
        long[] nice = { 1_000L, 10_000L, 100_000L, 1_000_000L, 5_000_000L, 10_000_000L, 30_000_000L, 60_000_000L,
                300_000_000L, 600_000_000L, 1_800_000_000L, 3_600_000_000L, 21_600_000_000L, 86_400_000_000L };
        for (long n : nice) {
            if (range / n <= 30) {
                return n;
            }
        }
        return 86_400_000_000L;
    }

    private static Map<String, Object> record(LogQuery.Hit h, ToolArgs args, boolean several, ZoneId zone) {
        LogRecord r = h.record();
        Map<String, Object> m = new LinkedHashMap<>();
        if (h.context()) {
            m.put("context", true);
        }
        m.put("at", location(r, several));
        m.put("time", r.timestamp() != null ? r.timestamp() : r.hasTime() ? time(r.micros(), zone) : null);
        m.put("level", r.level());
        if (r.thread() != null) {
            m.put("thread", r.thread());
        }
        if (r.logger() != null) {
            m.put("logger", r.logger());
        }
        int max = args.integer("max_message", 500);
        m.put("message", cut(r.message(), max));
        if (!r.fields().isEmpty()) {
            m.put("fields", r.fields());
        }
        Stacks stacks = args.choice("stack_traces", Stacks.class, Stacks.ONELINE);
        StackTrace t = stacks == Stacks.NONE ? null : r.stackTrace();
        List<String> app = args.strings("app_packages");
        if (t != null) {
            switch (stacks) {
                case ONELINE -> m.put("exception", StackTraces.oneLine(t, app));
                case TRIMMED -> m.put("stack_trace", StackTraces.render(t, true, app));
                case FULL -> m.put("stack_trace", StackTraces.render(t, false, app));
                default -> {
                }
            }
        } else if (!r.continuation().isEmpty()) {
            m.put("continuation", cut(String.join("\n", r.continuation()), max));
        }
        return m;
    }

    private static boolean several(List<Path> files) {
        return files.size() > 1;
    }

    private static String location(LogRecord r, boolean several) {
        return (several && r.file() != null ? r.file().getFileName() + ":" : "line ") + r.line();
    }

    /** ISO time; records from time-only logs (dated 1970-01-01) show the time of day only. */
    static String time(long micros, ZoneId zone) {
        Instant i = Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000), Math.floorMod(micros, 1_000_000) * 1000L);
        ZonedDateTime z = i.atZone(zone);
        if (z.toLocalDate().equals(LocalDate.of(1970, 1, 1))) {
            return z.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"));
        }
        return z.toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    static String cut(String s, int max) {
        if (s == null || max <= 0 || s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "… [" + (s.length() - max) + " more characters]";
    }
}
