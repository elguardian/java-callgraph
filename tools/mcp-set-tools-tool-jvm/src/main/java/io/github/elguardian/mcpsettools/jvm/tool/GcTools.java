package io.github.elguardian.mcpsettools.jvm.tool;

import io.github.elguardian.mcpsettools.tool.Tool;
import io.github.elguardian.mcpsettools.tool.Schema;
import io.github.elguardian.mcpsettools.tool.ToolArgs;
import io.github.elguardian.mcpsettools.tool.ToolException;
import io.github.elguardian.mcpsettools.tool.Results;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.elguardian.mcpsettools.jvm.gc.GcAnalysis;
import io.github.elguardian.mcpsettools.jvm.gc.GcEvent;
import io.github.elguardian.mcpsettools.jvm.gc.GcLog;
import io.github.elguardian.mcpsettools.jvm.gc.GcLogParser;
import io.github.elguardian.mcpsettools.jvm.heap.HeapHistogram;
import io.github.elguardian.mcpsettools.jvm.Jcmd;

/** GC logs and class histograms: pauses, overhead, allocation, leaks. */
final class GcTools {

    List<Tool> tools() {
        return List.of(gcLog(), histogram());
    }

    private Tool gcLog() {
        return new Tool("gc_log_analyze", "Analyze a GC log", """
                Reads GC logs: JDK 9+ unified logging (-Xlog:gc or gc*, any decorations; G1, Parallel, Serial, \
                ZGC, Shenandoah) and JDK 8 -XX:+PrintGCDetails (Parallel, CMS, G1). Reports the collector, pause \
                statistics per kind (count, total, p50/p90/p99, max), concurrent cycles, the share of time \
                spent paused overall and in the worst window, the allocation rate, the heap left after full \
                GCs over time (a growing trend close to the capacity suggests a leak), the longest pauses, full \
                GCs with their causes, and warnings such as to-space exhausted or allocation stalls.""", true,
                Schema.object().strings("files", "GC log files (rotated parts in order)", true)
                        .raw("from_seconds", "Only events after this JVM uptime", false, Map.of("type", "number"))
                        .raw("to_seconds", "Only events before this JVM uptime", false, Map.of("type", "number"))
                        .integer("top", "Longest pauses and full GCs listed", 10).tableFormat().build(),
                args -> {
                    GcLog log = read(args);
                    GcAnalysis a = new GcAnalysis(log, number(args, "from_seconds"), number(args, "to_seconds"));
                    if (a.events().isEmpty()) {
                        throw new ToolException("No GC events found: is it a GC log (-Xlog:gc or -XX:+PrintGCDetails)?");
                    }
                    int top = args.integer("top", 10);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("format", log.format());
                    m.put("collector", log.collector());
                    m.put("events", a.events().size());
                    m.put("span_seconds", round(a.end() - a.start(), 1));
                    List<Map<String, Object>> stats = a.pauseStats().stream().map(GcTools::stats).toList();
                    m.put("pauses", stats);
                    if (!a.concurrentStats().isEmpty()) {
                        m.put("concurrent", a.concurrentStats().stream().map(GcTools::stats).toList());
                    }
                    m.put("paused_percent", round(a.overhead(), 2));
                    GcAnalysis.Window w = a.worstWindow();
                    if (w != null) {
                        m.put("worst_window", Map.of("from_s", round(w.start(), 1), "to_s", round(w.end(), 1),
                                "paused_ms", round(w.pausedMs(), 1), "paused_percent", round(w.percent(), 1)));
                    }
                    m.put("allocation_mb_per_s", round(a.allocationRate() / 1024, 1));
                    GcAnalysis.Trend t = a.heapTrend();
                    if (t != null) {
                        Map<String, Object> tr = new LinkedHashMap<>();
                        tr.put("points", t.points());
                        tr.put("after_gc_first_mb", round(t.first() / 1024.0, 1));
                        tr.put("after_gc_last_mb", round(t.last() / 1024.0, 1));
                        if (t.capacity() > 0) {
                            tr.put("capacity_mb", round(t.capacity() / 1024.0, 1));
                        }
                        tr.put("mb_per_minute", round(t.kbPerMinute() / 1024, 2));
                        tr.put("leak_suspected", t.suspicious());
                        m.put("heap_after_gc", tr);
                    }
                    m.put("longest", a.longest(top).stream().map(GcTools::event).toList());
                    List<GcEvent> full = a.fullGcs();
                    if (!full.isEmpty()) {
                        m.put("full_gcs", full.size());
                        Map<String, Integer> causes = new LinkedHashMap<>();
                        full.forEach(e -> causes.merge(e.detail().isEmpty() ? "?" : e.detail(), 1, Integer::sum));
                        m.put("full_gc_causes", causes);
                    }
                    if (!log.warnings().isEmpty()) {
                        m.put("warnings", log.warnings());
                    }
                    Results.table(m, stats, null, args);
                    return m;
                });
    }

    private Tool histogram() {
        return new Tool("heap_histogram", "Compare class histograms", """
                Reads class histograms (jcmd GC.class_histogram, jmap -histo), several per file or several \
                files in order, or takes them from a running local JVM by pid, and shows which classes grow: \
                instances and bytes gained from the first to the last snapshot, and the classes growing in every \
                snapshot (leak candidates). Taking a histogram by pid counts live objects, which forces a full GC \
                in the target, unless all_objects=true.""", false,
                Schema.object().strings("files", "Histogram files, oldest first", false)
                        .string("pid", "Take the histograms from this local JVM", false)
                        .integer("count", "With pid: snapshots to take", 3)
                        .integer("interval_seconds", "With pid: seconds between snapshots", 30)
                        .bool("all_objects", "With pid: include unreachable objects (no full GC)", false)
                        .integer("top", "Classes listed", 20).tableFormat().build(),
                args -> {
                    List<HeapHistogram.Snapshot> snapshots = new ArrayList<>();
                    Map<String, Object> m = new LinkedHashMap<>();
                    if (args.has("pid")) {
                        int count = args.integer("count", 3);
                        if (count < 1 || count > 20) {
                            throw new ToolException("'count' must be 1 to 20");
                        }
                        for (int i = 0; i < count; i++) {
                            if (i > 0) {
                                Thread.sleep(Math.max(0, args.integer("interval_seconds", 30)) * 1000L);
                            }
                            args.progress(i, count, "histogram " + (i + 1) + "/" + count);
                            String text;
                            try {
                                text = args.bool("all_objects", false)
                                        ? Jcmd.run(args.require("pid"), Duration.ofMinutes(5), "GC.class_histogram", "-all")
                                        : Jcmd.run(args.require("pid"), Duration.ofMinutes(5), "GC.class_histogram");
                            } catch (java.io.IOException | IllegalArgumentException | IllegalStateException e) {
                                throw new ToolException(e.getMessage());
                            }
                            snapshots.addAll(HeapHistogram.parse("pid " + args.require("pid"), text, snapshots.size()));
                        }
                    } else if (args.has("files")) {
                        for (Path f : args.paths("files")) {
                            if (!Files.isRegularFile(f)) {
                                throw new ToolException("Not a file: " + f);
                            }
                            snapshots.addAll(HeapHistogram.parse(f.getFileName().toString(), Files.readString(f),
                                    snapshots.size()));
                        }
                    } else {
                        throw new ToolException("Give 'files' or 'pid'");
                    }
                    if (snapshots.isEmpty()) {
                        throw new ToolException("No class histogram found (looked for the 'num #instances #bytes class name' header)");
                    }
                    int top = args.integer("top", 20);
                    m.put("snapshots", snapshots.size());
                    m.put("total_mb", snapshots.stream().map(s -> round(s.bytes() / 1048576.0, 1)).toList());
                    List<Map<String, Object>> rows = new ArrayList<>();
                    if (snapshots.size() == 1) {
                        snapshots.get(0).entries().stream().limit(top).forEach(e -> {
                            Map<String, Object> r = new LinkedHashMap<>();
                            r.put("class", e.className());
                            r.put("instances", e.instances());
                            r.put("mb", round(e.bytes() / 1048576.0, 2));
                            rows.add(r);
                        });
                        m.put("largest", rows);
                    } else {
                        List<HeapHistogram.Growth> growth = HeapHistogram.diff(snapshots);
                        growth.stream().filter(g -> g.bytesDelta() > 0).limit(top).forEach(g -> {
                            Map<String, Object> r = new LinkedHashMap<>();
                            r.put("class", g.className());
                            r.put("instances_delta", g.instancesDelta());
                            r.put("mb_delta", round(g.bytesDelta() / 1048576.0, 2));
                            r.put("mb_last", round(g.lastBytes() / 1048576.0, 2));
                            r.put("growing_in_every_snapshot", g.growingEverywhere());
                            rows.add(r);
                        });
                        m.put("growth", rows);
                        m.put("leak_candidates", growth.stream().filter(HeapHistogram.Growth::growingEverywhere)
                                .filter(g -> g.bytesDelta() > 0).limit(top).map(HeapHistogram.Growth::className).toList());
                    }
                    Results.table(m, rows, null, args);
                    return m;
                });
    }

    static GcLog read(ToolArgs args) throws java.io.IOException {
        List<Path> files = args.paths("files");
        if (files.isEmpty()) {
            throw new ToolException("Give 'files'");
        }
        StringBuilder all = new StringBuilder();
        for (Path f : files) {
            if (!Files.isRegularFile(f)) {
                throw new ToolException("Not a file: " + f);
            }
            all.append(Files.readString(f)).append('\n');
        }
        return GcLogParser.parse(files.get(0).getFileName().toString(), all.toString());
    }

    private static Map<String, Object> stats(GcAnalysis.PauseStats s) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("kind", s.kind());
        r.put("count", s.count());
        r.put("total_ms", round(s.totalMs(), 1));
        r.put("p50_ms", round(s.p50(), 3));
        r.put("p90_ms", round(s.p90(), 3));
        r.put("p99_ms", round(s.p99(), 3));
        r.put("max_ms", round(s.maxMs(), 3));
        return r;
    }

    private static Map<String, Object> event(GcEvent e) {
        Map<String, Object> r = new LinkedHashMap<>();
        if (e.id() >= 0) {
            r.put("gc", e.id());
        }
        if (e.time() >= 0) {
            r.put("at_s", round(e.time(), 3));
        }
        r.put("kind", e.kind());
        if (!e.detail().isEmpty()) {
            r.put("cause", e.detail());
        }
        r.put("ms", round(e.millis(), 3));
        if (e.before() >= 0) {
            r.put("heap_mb", round(e.before() / 1024.0, 1) + " -> " + round(e.after() / 1024.0, 1)
                    + (e.capacity() > 0 ? " (" + round(e.capacity() / 1024.0, 1) + ")" : ""));
        }
        return r;
    }

    private static double number(ToolArgs args, String name) {
        return args.raw(name) instanceof Number n ? n.doubleValue() : -1;
    }

    static double round(double v, int digits) {
        double f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }
}
