package io.github.elguardian.mcpsettools.logs;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.ToLongFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * How the first line of a log record looks: compiled from a layout pattern
 * ({@code %d %-5p [%c] (%t) %s%e%n}), a preset name, or {@code regex:<java regex>} with named
 * groups {@code ts}, {@code level}, {@code thread}, {@code logger}, {@code message} (any other
 * named group becomes a field).
 */
public final class LogFormat {

    /** Built-in formats: name → layout or {@code regex:}. */
    public static final Map<String, String> PRESETS;
    private static final Map<String, String> PRESET_DATE_FORMATS = new HashMap<>();

    static {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("wildfly", "%d{yyyy-MM-dd HH:mm:ss,SSS} %-5p [%c] (%t) %s%e%n");
        p.put("wildfly-console", "%K{level}%d{HH:mm:ss,SSS} %-5p [%c] (%t) %s%e%n");
        p.put("logback", "%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n");
        p.put("logback-full", "%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n");
        p.put("log4j2", "%d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %-5level %logger{36} - %msg%n");
        p.put("spring-boot", "regex:^(?<ts>\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}\\.\\d{3}(?:Z|[+-]\\d{2}:?\\d{2})?)"
                + "\\s+(?<level>[A-Z]+)\\s+\\d+\\s+---\\s+(?:\\[[^\\]]*\\]\\s+)?\\[\\s*(?<thread>[^\\]]*?)\\]\\s+"
                + "(?<logger>\\S+)\\s*:\\s(?<message>.*)$");
        PRESET_DATE_FORMATS.put("spring-boot", "yyyy-MM-dd['T'][' ']HH:mm:ss.SSS[XXX][XX][X]");
        p.put("maven", "regex:^(?:(?<ts>\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}[.,]\\d{3}|\\d{2}:\\d{2}:\\d{2}[.,]\\d{3})\\s+)?"
                + "(?:\\[(?<thread>[^\\]]+)\\]\\s+)?\\[(?<level>INFO|WARNING|WARN|ERROR|DEBUG|TRACE)\\]\\s?(?<message>.*)$");
        PRESET_DATE_FORMATS.put("maven", "[yyyy-MM-dd ]HH:mm:ss[.SSS][,SSS]");
        p.put("iso", "regex:^(?<ts>\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d{1,9})?(?:Z|[+-]\\d{2}:?\\d{2})?)\\s+"
                + "(?:\\[(?<thread>[^\\]]+)\\]\\s+)?(?<level>TRACE|DEBUG|INFO|WARN|WARNING|ERROR|FATAL|SEVERE)?\\s*(?<message>.*)$");
        PRESET_DATE_FORMATS.put("iso", "yyyy-MM-dd['T'][' ']HH:mm:ss[.SSSSSSSSS][.SSSSSS][.SSS][,SSS][XXX][XX][X]");
        PRESETS = java.util.Collections.unmodifiableMap(p);
    }

    private static final List<String> FIELDS = List.of("ts", "level", "thread", "logger", "message");
    private static final Pattern EXCEPTION_HEADER = Pattern.compile("^(?:[a-zA-Z_$][\\w$]*\\.)+[\\w$]*(?:Exception|Error|Throwable)(?::.*)?$");

    private final String name;
    private final String spec;
    private final Pattern regex;
    private final List<String> extraGroups;
    private final ToLongFunction<String> time;

    private LogFormat(String name, String spec, Pattern regex, String dateFormat, String zone) {
        this.name = name;
        this.spec = spec;
        this.regex = regex;
        this.time = dateFormat == null ? s -> LogRecord.NO_TIME : DateFormats.parser(dateFormat, zone);
        List<String> extra = new ArrayList<>();
        Matcher g = Pattern.compile("\\(\\?<([a-zA-Z][a-zA-Z0-9]*)>").matcher(regex.pattern());
        while (g.find()) {
            if (!FIELDS.contains(g.group(1)) && !extra.contains(g.group(1))) {
                extra.add(g.group(1));
            }
        }
        this.extraGroups = List.copyOf(extra);
    }

    /**
     * A format from a preset name, {@code regex:...} or a layout. {@code dateFormat} overrides the
     * timestamp pattern (needed for {@code regex:} formats whose {@code ts} is not ISO-like).
     */
    public static LogFormat of(String spec, String dateFormat, String zone) {
        String s = spec.strip();
        String name = "custom";
        if (PRESETS.containsKey(s)) {
            name = s;
            if (dateFormat == null) {
                dateFormat = PRESET_DATE_FORMATS.get(s);
            }
            s = PRESETS.get(s);
        }
        try {
            if (s.startsWith("regex:")) {
                String re = s.substring("regex:".length());
                return new LogFormat(name, spec, Pattern.compile(re), dateFormat == null ? defaultDateFormat(re) : dateFormat,
                        zone);
            }
            LayoutCompiler.Compiled c = LayoutCompiler.compile(s);
            return new LogFormat(name, spec, Pattern.compile(c.regex()), dateFormat == null ? c.dateFormat() : dateFormat,
                    zone == null ? c.zone() : zone);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("Invalid log pattern regex: " + e.getDescription(), e);
        }
    }

    private static String defaultDateFormat(String regex) {
        return regex.contains("(?<ts>") ? PRESET_DATE_FORMATS.get("iso") : null;
    }

    /** The preset parsing most of the sample lines (at least half of the lines that start records), if any. */
    public static Optional<LogFormat> detect(List<String> sample) {
        LogFormat best = null;
        int bestScore = 0;
        int bestTimed = 0;
        // continuation lines (stack traces, wrapped messages) do not start records in any format
        List<String> lines = sample.stream().filter(l -> !l.isBlank() && !Character.isWhitespace(l.charAt(0))
                && !l.startsWith("Caused by:") && !l.startsWith("Suppressed:") && !EXCEPTION_HEADER.matcher(l).matches()).toList();
        for (String preset : PRESETS.keySet()) {
            LogFormat f = of(preset, null, null);
            int score = 0;
            int timed = 0;
            for (String l : lines) {
                Optional<LogRecord> r = f.parseLine(l, null, 0);
                if (r.isPresent()) {
                    score++;
                    if (r.get().hasTime()) {
                        timed++;
                    }
                }
            }
            if (score > bestScore || score == bestScore && timed > bestTimed) {
                best = f;
                bestScore = score;
                bestTimed = timed;
            }
        }
        return best != null && bestScore * 2 >= lines.size() && bestScore > 0 ? Optional.of(best) : Optional.empty();
    }

    /** A record start, or empty when the line is a continuation line. */
    public Optional<LogRecord> parseLine(String line, Path file, long lineNumber) {
        Matcher m = regex.matcher(line);
        if (!m.matches()) {
            return Optional.empty();
        }
        String ts = group(m, "ts");
        long micros = ts == null ? LogRecord.NO_TIME : time.applyAsLong(ts);
        String raw = group(m, "level");
        Map<String, String> fields = new LinkedHashMap<>();
        for (String g : extraGroups) {
            String v = group(m, g);
            if (v != null) {
                fields.put(g, v.strip());
            }
        }
        String msg = group(m, "message");
        return Optional.of(new LogRecord(file, lineNumber, micros, ts, Levels.normalize(raw), raw, strip(group(m, "thread")),
                strip(group(m, "logger")), msg == null ? line : msg, fields, List.of()));
    }

    private static String strip(String s) {
        return s == null ? null : s.strip();
    }

    private static String group(Matcher m, String name) {
        try {
            return m.group(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public String name() {
        return name;
    }

    public String spec() {
        return spec;
    }

    public String regex() {
        return regex.pattern();
    }
}
