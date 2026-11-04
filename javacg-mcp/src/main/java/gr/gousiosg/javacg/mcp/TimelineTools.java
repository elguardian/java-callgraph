package gr.gousiosg.javacg.mcp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import gr.gousiosg.javacg.analyze.MethodPattern;
import gr.gousiosg.javacg.devtools.logs.LogFormat;
import gr.gousiosg.javacg.devtools.logs.LogReader;
import gr.gousiosg.javacg.devtools.maven.MavenLogParser;
import gr.gousiosg.javacg.devtools.timeline.Analysis;
import gr.gousiosg.javacg.devtools.timeline.LogSource;
import gr.gousiosg.javacg.devtools.timeline.MavenSource;
import gr.gousiosg.javacg.devtools.timeline.RecordingSource;
import gr.gousiosg.javacg.devtools.timeline.Renderers;
import gr.gousiosg.javacg.devtools.timeline.Span;
import gr.gousiosg.javacg.devtools.timeline.Timeline;

/** Concurrency diagrams: what each thread did over time, from recordings, logs, Maven builds or given events. */
final class TimelineTools {

    private enum Source {
        RECORDING, LOG, MAVEN, GC, JFR, EVENTS
    }

    List<ToolDef> tools() {
        return List.of(timeline());
    }

    private ToolDef timeline() {
        Map<String, Object> rule = Map.of("type", "object", "properties", Map.of(
                "name", Map.of("type", "string"),
                "start", Map.of("type", "string", "description", "Regex on the message opening the span"),
                "end", Map.of("type", "string", "description", "Regex on the message closing it"),
                "key", Map.of("type", "string", "description", "Named group in both regexes pairing start and end across "
                        + "threads (e.g. a request id); without it they pair on the same thread")),
                "required", List.of("name", "start", "end"));
        Map<String, Object> marker = Map.of("type", "object", "properties", Map.of("name", Map.of("type", "string"),
                "match", Map.of("type", "string", "description", "Regex on the message and its stack trace")),
                "required", List.of("name", "match"));
        Map<String, Object> event = Map.of("type", "object", "properties", Map.of(
                "thread", Map.of("type", "string"), "label", Map.of("type", "string"),
                "start", Map.of("type", List.of("number", "string"), "description", "Milliseconds or ISO time"),
                "end", Map.of("type", List.of("number", "string"), "description", "Milliseconds or ISO time"),
                "category", Map.of("type", "string"), "process", Map.of("type", "string")),
                "required", List.of("thread", "start", "end", "label"));
        return new ToolDef("timeline", "Draw a thread timeline", """
                Draws what threads did over time and measures their concurrency, from: a javacg recording \
                (one block per call of the methods matching 'methods', plus lock wait/hold blocks when the \
                agent ran with locks=true; several recordings of a cluster share one clock), log files \
                (blocks opened and closed by 'spans' rules, on one thread or paired across threads by a \
                key group, and 'markers' as instants), a maven_run log (module builds per builder thread, \
                for -T builds) or explicit 'events'. Returns the analysis (busy time per thread, most \
                threads busy at once, the same block running on several threads at the same time, lock \
                waits, longest blocks) and the diagram: ascii swimlanes, mermaid gantt, plantuml concise, \
                chrome (Trace Event JSON for Perfetto or chrome://tracing) or html (self-contained SVG \
                page). chrome and html need 'output'.""", false,
                Schema.object().choice("source", "Where the blocks come from", null, "recording", "log", "maven", "gc", "jfr", "events")
                        .strings("trace", "recording: recording directories or an agent out= directory", false)
                        .string("methods", "recording: methods drawn. " + AnalysisTools.PATTERN_HELP + " Default: all", false)
                        .bool("locks", "recording: draw lock waits and holds", true)
                        .raw("min_duration_ms", "recording: leave out calls shorter than this", false,
                                Map.of("type", "number", "default", 0))
                        .strings("files", "log: log files (one process each)", false)
                        .string("pattern", "log: " + LogTools.PATTERN_HELP + ". Default auto", false)
                        .string("date_format", "log: timestamp format overriding the pattern's", false)
                        .raw("spans", "log: span rules", false, Map.of("type", "array", "items", rule))
                        .raw("markers", "log: records drawn as instants", false, Map.of("type", "array", "items", marker))
                        .string("log_file", "maven: a log written by maven_run (it has timestamps and threads)", false)
                        .strings("gc_files", "gc: GC log files; pauses become blocks on a GC lane", false)
                        .string("jfr_file", "jfr: a recording; monitor waits, parks, I/O and GC pauses become blocks", false)
                        .raw("events", "events: the blocks", false, Map.of("type", "array", "items", event))
                        .raw("from_ms", "Window start, milliseconds after the timeline start", false, Map.of("type", "number"))
                        .raw("to_ms", "Window end, milliseconds after the timeline start", false, Map.of("type", "number"))
                        .string("threads", "Regex: only threads whose name it finds", false)
                        .string("labels", "Regex: only blocks whose label it finds", false)
                        .choice("format", "Diagram format", "ascii", "ascii", "mermaid", "plantuml", "chrome", "html")
                        .string("output", "Write the diagram to this file (required for chrome and html)", false)
                        .integer("width", "ascii: columns of the time axis", 120)
                        .integer("rows", "ascii, plantuml, html: rows per thread for nested or overlapping blocks", 4)
                        .integer("max_spans", "Most blocks drawn, longest first (default 2000 for text formats, "
                                + "200000 for chrome and html)", 0)
                        .bool("diagram_only", "Return only the diagram text", false)
                        .string("zone", "Time zone for times without one and for display (default: the server's)", false)
                        .build(),
                args -> {
                    ZoneId zone = zone(args);
                    Renderers.Format format = args.choice("format", Renderers.Format.class, Renderers.Format.ASCII);
                    boolean file = format == Renderers.Format.CHROME || format == Renderers.Format.HTML;
                    if (file && !args.has("output")) {
                        throw new ToolException(Views.lower(format) + " diagrams are written to a file: give 'output'");
                    }
                    int maxSpans = args.integer("max_spans", 0) > 0 ? args.integer("max_spans", 0) : file ? 200_000 : 2_000;
                    Timeline full = read(args, zone, Math.max(maxSpans, 200_000));
                    if (full.isEmpty()) {
                        throw new ToolException("Nothing to draw: no blocks or marks came out of the source"
                                + (full.notes().isEmpty() ? "" : " (" + String.join("; ", full.notes()) + ")"));
                    }
                    long t0 = full.start();
                    Long from = args.raw("from_ms") instanceof Number n ? t0 + Math.round(n.doubleValue() * 1000) : null;
                    Long to = args.raw("to_ms") instanceof Number n ? t0 + Math.round(n.doubleValue() * 1000) : null;
                    Pattern threads = regex(args, "threads");
                    Pattern labels = regex(args, "labels");
                    Timeline t = full.filter(from, to, x -> threads == null || threads.matcher(x).find(),
                            x -> labels == null || labels.matcher(x).find()).limit(maxSpans);
                    if (t.isEmpty()) {
                        throw new ToolException("Nothing left to draw after the window and filters");
                    }
                    String diagram = Renderers.render(t, format, Math.max(20, args.integer("width", 120)),
                            Math.max(1, args.integer("rows", 4)), zone);
                    Path out = null;
                    if (args.has("output")) {
                        out = ToolArgs.path(args.require("output"));
                        Files.createDirectories(out.toAbsolutePath().getParent());
                        Files.writeString(out, diagram);
                    }
                    if (args.bool("diagram_only", false) && !file) {
                        return diagram;
                    }
                    Map<String, Object> m = summary(t, zone);
                    if (out != null) {
                        m.put("output", out.toString());
                    }
                    if (!file) {
                        m.put("diagram", diagram);
                    }
                    return m;
                });
    }

    private static Timeline read(ToolArgs args, ZoneId zone, int maxSpans) throws IOException {
        Source source = args.choice("source", Source.class, null);
        try {
            return switch (source) {
                case RECORDING -> {
                    List<Path> trace = args.paths("trace");
                    if (trace.isEmpty()) {
                        throw new ToolException("source=recording needs 'trace'");
                    }
                    MethodPattern methods = args.has("methods") ? Models.pattern(args.require("methods")) : null;
                    long min = args.raw("min_duration_ms") instanceof Number n ? Math.round(n.doubleValue() * 1000) : 0;
                    yield RecordingSource.read(trace, methods, args.bool("locks", true), min, maxSpans);
                }
                case LOG -> {
                    List<Path> files = args.paths("files");
                    if (files.isEmpty()) {
                        throw new ToolException("source=log needs 'files'");
                    }
                    for (Path f : files) {
                        if (!Files.isRegularFile(f)) {
                            throw new ToolException("Not a file: " + f);
                        }
                    }
                    LogFormat format = logFormat(args, files.get(0), zone);
                    List<LogSource.Rule> rules = new ArrayList<>();
                    for (Map<String, Object> r : objects(args, "spans")) {
                        rules.add(new LogSource.Rule(str(r, "name"), compile(str(r, "start"), "spans.start"),
                                compile(str(r, "end"), "spans.end"), r.get("key") == null ? null : str(r, "key")));
                    }
                    List<LogSource.Marker> markers = new ArrayList<>();
                    for (Map<String, Object> mk : objects(args, "markers")) {
                        markers.add(new LogSource.Marker(str(mk, "name"), compile(str(mk, "match"), "markers.match")));
                    }
                    if (rules.isEmpty() && markers.isEmpty()) {
                        throw new ToolException("source=log needs 'spans' rules and/or 'markers', e.g. spans: [{name: "
                                + "\"request\", start: \"begin request (?<id>\\\\w+)\", end: \"end request (?<id>\\\\w+)\", "
                                + "key: \"id\"}]");
                    }
                    yield LogSource.read(files, format, rules, markers);
                }
                case MAVEN -> {
                    Path log = args.has("log_file") ? ToolArgs.path(args.require("log_file")) : null;
                    if (log == null || !Files.isRegularFile(log)) {
                        throw new ToolException("source=maven needs 'log_file', the log_file of a maven_run result");
                    }
                    yield MavenSource.read(MavenLogParser.parse(log));
                }
                case GC -> gc(args);
                case JFR -> {
                    Path f = args.has("jfr_file") ? ToolArgs.path(args.require("jfr_file")) : null;
                    if (f == null || !Files.isRegularFile(f)) {
                        throw new ToolException("source=jfr needs 'jfr_file', a .jfr recording");
                    }
                    yield gr.gousiosg.javacg.devtools.timeline.JfrSource.read(f, maxSpans);
                }
                case EVENTS -> events(args, zone);
            };
        } catch (IllegalArgumentException e) {
            throw new ToolException(e.getMessage());
        }
    }

    private static Timeline events(ToolArgs args, ZoneId zone) {
        List<Span> spans = new ArrayList<>();
        for (Map<String, Object> e : objects(args, "events")) {
            long start = time(e.get("start"), zone);
            long end = time(e.get("end"), zone);
            spans.add(new Span(e.get("process") == null ? "events" : str(e, "process"), str(e, "thread"), start, end,
                    str(e, "label"), e.get("category") == null ? "span" : str(e, "category"), 0, Map.of()));
        }
        if (spans.isEmpty()) {
            throw new ToolException("source=events needs 'events'");
        }
        return new Timeline(spans, List.of(), List.of());
    }

    /** GC pauses (and concurrent cycles on their own lane) at wall-clock time when logged, else at uptime. */
    private static Timeline gc(ToolArgs args) throws IOException {
        List<Path> files = args.paths("gc_files");
        if (files.isEmpty()) {
            throw new ToolException("source=gc needs 'gc_files'");
        }
        Map<String, Object> pass = new LinkedHashMap<>();
        pass.put("files", files.stream().map(Path::toString).toList());
        gr.gousiosg.javacg.devtools.gc.GcLog log = GcTools.read(new ToolArgs(pass));
        List<Span> spans = new ArrayList<>();
        for (var e : log.events()) {
            long start = e.wall() != Long.MIN_VALUE ? e.wall() : e.uptime() >= 0 ? Math.round(e.uptime() * 1_000_000) : -1;
            if (start < 0) {
                continue;
            }
            Map<String, String> a = new LinkedHashMap<>();
            if (!e.detail().isEmpty()) {
                a.put("cause", e.detail());
            }
            if (e.before() >= 0) {
                a.put("heap_kb", e.before() + " -> " + e.after());
            }
            spans.add(new Span("gc", e.pause() ? "GC pauses" : "GC concurrent", start, start + Math.round(e.millis() * 1000),
                    e.kind(), e.pause() ? "gc-pause" : "gc-concurrent", 0, a));
        }
        return new Timeline(spans, List.of(), List.of());
    }

    /** Milliseconds (epoch or relative) as a number, or an ISO date-time; returns microseconds. */
    private static long time(Object v, ZoneId zone) {
        if (v instanceof Number n) {
            return Math.round(n.doubleValue() * 1000);
        }
        String s = String.valueOf(v).strip().replace(' ', 'T');
        try {
            Instant i;
            try {
                i = OffsetDateTime.parse(s).toInstant();
            } catch (DateTimeParseException e) {
                i = LocalDateTime.parse(s).atZone(zone).toInstant();
            }
            return i.getEpochSecond() * 1_000_000 + i.getNano() / 1000;
        } catch (DateTimeParseException e) {
            throw new ToolException("Event time '" + v + "' is neither milliseconds nor an ISO date-time");
        }
    }

    private Map<String, Object> summary(Timeline t, ZoneId zone) {
        Analysis a = new Analysis(t, 10);
        long t0 = a.start();
        long range = Math.max(1, a.end() - t0);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("start", Renderers.clock(t0, zone));
        m.put("end", Renderers.clock(a.end(), zone));
        m.put("duration", Renderers.duration(a.end() - t0));
        m.put("threads", t.lanes().size());
        m.put("spans", t.spans().size());
        m.put("marks", t.marks().size());
        m.put("max_concurrency", a.maxConcurrency());
        m.put("max_concurrency_at", "+" + Renderers.duration(a.maxConcurrencyAt() - t0));
        m.put("lanes", a.lanes().stream().map(l -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("thread", lane(t, l.lane()));
            r.put("spans", l.spans());
            r.put("busy", Renderers.duration(l.busy()));
            r.put("busy_percent", Math.round(100.0 * l.busy() / range));
            return r;
        }).toList());
        m.put("blocks", a.labels().stream().limit(15).map(l -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("block", l.label());
            r.put("count", l.count());
            r.put("total", Renderers.duration(l.total()));
            r.put("max", Renderers.duration(l.max()));
            return r;
        }).toList());
        if (a.overlapCount() > 0) {
            m.put("concurrent_same_block", a.overlapCount());
            m.put("overlaps", a.overlaps().stream().map(o -> {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("block", o.label());
                r.put("threads", List.of(lane(t, o.laneA()), lane(t, o.laneB())));
                r.put("overlap", Renderers.duration(o.duration()));
                r.put("at", "+" + Renderers.duration(o.start() - t0));
                return r;
            }).toList());
        }
        if (!a.locks().isEmpty()) {
            m.put("locks", a.locks().stream().limit(15).map(l -> {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("lock", l.lock());
                r.put("waits", l.waits());
                r.put("total_wait", Renderers.duration(l.totalWait()));
                r.put("max_wait", Renderers.duration(l.maxWait()));
                r.put("holds", l.holds());
                r.put("total_held", Renderers.duration(l.totalHold()));
                return r;
            }).toList());
        }
        if (!t.notes().isEmpty()) {
            m.put("notes", t.notes());
        }
        return m;
    }

    /** {@code process / thread}, or the thread alone when there is one process. */
    private static String lane(Timeline t, String lane) {
        return t.multiProcess() ? lane : lane.substring(lane.indexOf(" / ") + 3);
    }

    private static LogFormat logFormat(ToolArgs args, Path first, ZoneId zone) throws IOException {
        String spec = args.string("pattern", "auto");
        if (spec.equals("auto")) {
            LogFormat detected = LogFormat.detect(LogReader.head(first, 200)).orElseThrow(() -> new ToolException(
                    "Could not recognize the log format of " + first + ": pass 'pattern'. " + LogTools.PATTERN_HELP));
            spec = detected.name();
        }
        return LogFormat.of(spec, args.string("date_format", null), zone.getId());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> objects(ToolArgs args, String name) {
        Object v = args.raw(name);
        if (v == null) {
            return List.of();
        }
        if (!(v instanceof List<?> l) || !l.stream().allMatch(x -> x instanceof Map)) {
            throw new ToolException("'" + name + "' must be an array of objects");
        }
        return (List<Map<String, Object>>) v;
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null || v.toString().isBlank()) {
            throw new ToolException("Missing '" + key + "' in " + m);
        }
        return v.toString();
    }

    private static Pattern compile(String regex, String what) {
        try {
            return Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            throw new ToolException("'" + what + "' is not a valid regex: " + e.getDescription());
        }
    }

    private static Pattern regex(ToolArgs args, String name) {
        return args.has(name) ? compile(args.require(name), name) : null;
    }

    private static ZoneId zone(ToolArgs args) {
        try {
            return args.has("zone") ? ZoneId.of(args.require("zone")) : ZoneId.systemDefault();
        } catch (java.time.DateTimeException e) {
            throw new ToolException("Unknown zone " + args.string("zone", null));
        }
    }
}
