package io.github.elguardian.mcpsettools.mcp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.regex.Pattern;

import io.github.elguardian.mcpsettools.timeline.Analysis;
import io.github.elguardian.mcpsettools.timeline.Renderers;
import io.github.elguardian.mcpsettools.timeline.Timeline;
import io.github.elguardian.mcpsettools.timeline.TimelineSource;
import io.github.elguardian.mcpsettools.tool.Results;
import io.github.elguardian.mcpsettools.tool.Schema;
import io.github.elguardian.mcpsettools.tool.Tool;
import io.github.elguardian.mcpsettools.tool.ToolArgs;
import io.github.elguardian.mcpsettools.tool.ToolContext;
import io.github.elguardian.mcpsettools.tool.ToolException;

/** Concurrency diagrams: what each thread did over time, from any {@link TimelineSource} on the class path. */
final class TimelineTools {

    private final ToolContext context;
    private final List<TimelineSource> sources;

    TimelineTools(ToolContext context) {
        this.context = context;
        List<TimelineSource> found = new ArrayList<>();
        ServiceLoader.load(TimelineSource.class, context.classLoader()).forEach(found::add);
        found.sort(Comparator.comparingInt(TimelineSource::order).thenComparing(TimelineSource::name));
        Map<String, TimelineSource> byName = new LinkedHashMap<>();
        for (TimelineSource src : found) {
            if (byName.putIfAbsent(src.name(), src) != null) {
                context.log().println("[mcp-set-tools] timeline source " + src.name() + " of " + src.getClass().getName()
                        + " ignored: the name is taken");
            }
        }
        this.sources = List.copyOf(byName.values());
    }

    List<Tool> tools() {
        return List.of(timeline());
    }

    private Tool timeline() {
        Schema schema = Schema.object().choice("source", "Where the blocks come from", null,
                sources.stream().map(TimelineSource::name).toArray(String[]::new));
        sources.forEach(src -> src.arguments(schema));
        return new Tool("timeline", "Draw a thread timeline", """
                Draws what threads did over time and measures their concurrency, from: a mcp-set-tools recording \
                (one block per call of the methods matching 'methods', plus lock wait/hold blocks when the \
                agent ran with locks=true; several recordings of a cluster share one clock), log files \
                (blocks opened and closed by 'spans' rules, on one thread or paired across threads by a \
                key group, and 'markers' as instants), a maven_run log (module builds per builder thread, \
                for -T builds) or explicit 'events'. Returns the analysis (busy time per thread, most \
                threads busy at once, the same block running on several threads at the same time, lock \
                waits, longest blocks) and the diagram: ascii swimlanes, mermaid gantt, plantuml concise, \
                chrome (Trace Event JSON for Perfetto or chrome://tracing) or html (self-contained SVG \
                page). chrome and html need 'output'.""", false,
                schema
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
                        throw new ToolException(Results.lower(format) + " diagrams are written to a file: give 'output'");
                    }
                    int maxSpans = args.integer("max_spans", 0) > 0 ? args.integer("max_spans", 0) : file ? 200_000 : 2_000;
                    Timeline full = read(args, new TimelineSource.Request(zone, Math.max(maxSpans, 200_000), context));
                    if (full.isEmpty()) {
                        throw new ToolException("Nothing to draw: no blocks or marks came out of the source"
                                + (full.notes().isEmpty() ? "" : " (" + String.join("; ", full.notes()) + ")"));
                    }
                    long t0 = full.start();
                    Long from = args.raw("from_ms") instanceof Number n ? t0 + Math.round(n.doubleValue() * 1000) : null;
                    Long to = args.raw("to_ms") instanceof Number n ? t0 + Math.round(n.doubleValue() * 1000) : null;
                    Pattern threads = args.regex("threads");
                    Pattern labels = args.regex("labels");
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

    private Timeline read(ToolArgs args, TimelineSource.Request request) throws Exception {
        String name = args.require("source");
        TimelineSource source = sources.stream().filter(x -> x.name().equals(name)).findFirst().orElseThrow(() -> new ToolException(
                "Unknown source " + name + "; sources: " + sources.stream().map(TimelineSource::name).toList()));
        try {
            return source.read(args, request);
        } catch (IllegalArgumentException e) {
            throw new ToolException(e.getMessage());
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

    private static ZoneId zone(ToolArgs args) {
        try {
            return args.has("zone") ? ZoneId.of(args.require("zone")) : ZoneId.systemDefault();
        } catch (java.time.DateTimeException e) {
            throw new ToolException("Unknown zone " + args.string("zone", null));
        }
    }
}
