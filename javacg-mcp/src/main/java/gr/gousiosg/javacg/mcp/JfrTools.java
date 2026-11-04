package gr.gousiosg.javacg.mcp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import gr.gousiosg.javacg.devtools.jfr.JfrAnalysis;
import gr.gousiosg.javacg.devtools.jvm.Jcmd;

/** JDK Flight Recorder: record a running JVM, summarize a recording. */
final class JfrTools {

    private static final List<String> SECTIONS = List.of("cpu", "locks", "allocation", "io", "exceptions", "gc");

    private final Jobs jobs;

    JfrTools(Jobs jobs) {
        this.jobs = jobs;
    }

    List<ToolDef> tools() {
        return List.of(analyze());
    }

    private ToolDef analyze() {
        return new ToolDef("jfr_analyze", "Analyze a JFR recording", """
                Summarizes a JDK Flight Recorder file, or records a running local JVM first (jcmd JFR.start for \
                'seconds', settings 'profile' by default; production-safe, a few percent overhead). Sections: \
                cpu (hot methods by own samples and anywhere on the stack, the first application frame, hot \
                stacks, threads), locks (monitor contention and parking by class and waiting frame, with total and \
                longest wait), allocation (by class and allocation site), io (socket and file reads/writes by \
                host or path), exceptions (throwables created, errors thrown), gc (pauses by collector and \
                cause), and CPU load. JFR only records waits and I/O above the thresholds of its settings.""", true,
                Schema.object().string("file", "A .jfr recording", false)
                        .string("pid", "Record this local JVM first", false)
                        .integer("seconds", "With pid: how long to record", 30)
                        .choice("settings", "With pid: JFR settings", "profile", "profile", "default")
                        .bool("background", "With pid: return a job_id at once and record in the background", false)
                        .strings("sections", "Sections to return (default all): cpu, locks, allocation, io, exceptions, gc",
                                false)
                        .string("threads", "Regex: only events of threads whose name it finds", false)
                        .string("from", "Start time (ISO date-time)", false)
                        .string("to", "End time (ISO date-time)", false)
                        .strings("app_packages", "Application package prefixes, e.g. org.acme", false)
                        .integer("top", "Entries per list", 10)
                        .integer("stack_depth", "Frames in hot stacks", 5).tableFormat().build(),
                args -> {
                    if (args.has("pid")) {
                        if (args.bool("background", false)) {
                            Jobs.Job job = jobs.start("jfr_analyze", j -> {
                                Path f = record(args, j);
                                return analyze(f, args);
                            });
                            return Jobs.started(job, "The recording");
                        }
                        return analyze(record(args, null), args);
                    }
                    if (!args.has("file")) {
                        throw new ToolException("Give 'file' or 'pid'");
                    }
                    Path f = ToolArgs.path(args.require("file"));
                    if (!Files.isRegularFile(f)) {
                        throw new ToolException("Not a file: " + f);
                    }
                    return analyze(f, args);
                });
    }

    /** jcmd JFR.start with a duration, then waits for the file to be written. */
    static Path record(ToolArgs args, Jobs.Job job) throws Exception {
        String pid = args.require("pid");
        int seconds = args.integer("seconds", 30);
        if (seconds < 1 || seconds > 3600) {
            throw new ToolException("'seconds' must be 1 to 3600");
        }
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "javacg-mcp", "jfr");
        Files.createDirectories(dir);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path file = dir.resolve("recording-" + pid + "-" + stamp + ".jfr");
        try {
            Jcmd.run(pid, Duration.ofSeconds(60), "JFR.start", "name=javacg-" + stamp, "duration=" + seconds + "s",
                    "settings=" + args.string("settings", "profile"), "filename=" + file);
        } catch (java.io.IOException | IllegalArgumentException | IllegalStateException e) {
            throw new ToolException(e.getMessage());
        }
        if (job != null) {
            job.progress("recording " + pid + " for " + seconds + " s into " + file);
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(seconds + 60L).toNanos();
        Thread.sleep(seconds * 1000L);
        long lastSize = -1;
        while (System.nanoTime() < deadline) {
            if (job != null && job.cancelled()) {
                throw new InterruptedException();
            }
            if (Files.isRegularFile(file)) {
                long size = Files.size(file);
                if (size > 0 && size == lastSize) {
                    return file;
                }
                lastSize = size;
            }
            Thread.sleep(500);
        }
        throw new ToolException("The recording " + file + " was not written: did the JVM exit?");
    }

    private static Map<String, Object> analyze(Path file, ToolArgs args) throws Exception {
        Pattern threads = args.has("threads") ? Pattern.compile(args.require("threads")) : null;
        JfrAnalysis.Options o = new JfrAnalysis.Options(threads, time(args, "from"), time(args, "to"),
                args.strings("app_packages"), args.integer("stack_depth", 5));
        JfrAnalysis a;
        try {
            a = JfrAnalysis.read(file, o);
        } catch (java.io.IOException e) {
            throw new ToolException("Cannot read " + file + " as a JFR recording: " + e.getMessage());
        }
        int top = args.integer("top", 10);
        Set<String> sections = args.has("sections") ? Set.copyOf(args.strings("sections")) : Set.copyOf(SECTIONS);
        for (String s : sections) {
            if (!SECTIONS.contains(s)) {
                throw new ToolException("Unknown section " + s + "; sections: " + SECTIONS);
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("file", file.toString());
        if (a.start() != null) {
            m.put("start", a.start().toString());
            m.put("seconds", Math.round(Duration.between(a.start(), a.end()).toMillis() / 100.0) / 10.0);
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        a.eventCounts().entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(15)
                .forEach(e -> counts.put(e.getKey(), e.getValue()));
        m.put("events", counts);
        List<Map<String, Object>> tableRows = new ArrayList<>();
        if (sections.contains("cpu")) {
            Map<String, Object> cpu = new LinkedHashMap<>();
            cpu.put("samples", a.samples());
            List<Map<String, Object>> self = samples(a.hotSelf(top), a.samples(), "method");
            tableRows.addAll(self);
            cpu.put("self", self);
            cpu.put("total", samples(a.hotTotal(top), a.samples(), "method"));
            if (!a.hotApp(1).isEmpty()) {
                cpu.put("first_app_frame", samples(a.hotApp(top), a.samples(), "frame"));
            }
            cpu.put("stacks", samples(a.hotStacks(Math.min(top, 5)), a.samples(), "stack"));
            cpu.put("threads", samples(a.sampleThreads(top), a.samples(), "thread"));
            double[] load = a.cpuLoad();
            if (load != null) {
                cpu.put("jvm_cpu_avg_percent", Math.round(load[0] * 1000) / 10.0);
                cpu.put("jvm_cpu_max_percent", Math.round(load[1] * 1000) / 10.0);
                cpu.put("machine_cpu_avg_percent", Math.round(load[2] * 1000) / 10.0);
            }
            m.put("cpu", cpu);
        }
        if (sections.contains("locks")) {
            Map<String, Object> locks = new LinkedHashMap<>();
            locks.put("monitor_contention", waits(a.monitors(top)));
            locks.put("parking", waits(a.parks(top)));
            m.put("locks", locks);
        }
        if (sections.contains("allocation")) {
            Map<String, Object> alloc = new LinkedHashMap<>();
            alloc.put("by_class", amounts(a.allocByClass(top), "class"));
            alloc.put("by_site", amounts(a.allocBySite(top), "site"));
            m.put("allocation", alloc);
        }
        if (sections.contains("io")) {
            Map<String, Object> io = new LinkedHashMap<>();
            io.put("sockets", io(a.sockets(top), "endpoint"));
            io.put("files", io(a.files(top), "path"));
            m.put("io", io);
        }
        if (sections.contains("exceptions")) {
            Map<String, Object> ex = new LinkedHashMap<>();
            if (a.throwables() >= 0) {
                ex.put("throwables_created", a.throwables());
            }
            ex.put("errors_thrown", a.errors(top).stream().map(g -> {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("class", g.key);
                r.put("count", g.count);
                if (g.example != null) {
                    r.put("example_message", g.example);
                }
                return r;
            }).toList());
            m.put("exceptions", ex);
        }
        if (sections.contains("gc")) {
            m.put("gc", waits(a.gcs(top)).stream().map(r -> {
                r.put("collector", r.remove("what"));
                return r;
            }).toList());
        }
        Views.table(m, tableRows, null, args);
        return m;
    }

    private static List<Map<String, Object>> samples(List<JfrAnalysis.Group> groups, long total, String key) {
        return groups.stream().map(g -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put(key, g.key);
            r.put("samples", g.count);
            r.put("percent", total == 0 ? 0 : Math.round(1000.0 * g.count / total) / 10.0);
            return r;
        }).toList();
    }

    private static List<Map<String, Object>> waits(List<JfrAnalysis.Group> groups) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JfrAnalysis.Group g : groups) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("what", g.key);
            r.put("count", g.count);
            r.put("total_ms", Math.round(g.totalMs * 10) / 10.0);
            r.put("max_ms", Math.round(g.maxMs * 10) / 10.0);
            if (g.example != null) {
                r.put("example_thread", g.example);
            }
            rows.add(r);
        }
        return rows;
    }

    private static List<Map<String, Object>> amounts(List<JfrAnalysis.Group> groups, String key) {
        return groups.stream().map(g -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put(key, g.key);
            r.put("samples", g.count);
            r.put("mb", Math.round(g.amount / 104857.6) / 10.0);
            return r;
        }).toList();
    }

    private static List<Map<String, Object>> io(List<JfrAnalysis.Group> groups, String key) {
        return groups.stream().map(g -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put(key, g.key);
            r.put("count", g.count);
            r.put("total_ms", Math.round(g.totalMs * 10) / 10.0);
            r.put("max_ms", Math.round(g.maxMs * 10) / 10.0);
            r.put("bytes", g.amount);
            return r;
        }).toList();
    }

    private static Instant time(ToolArgs args, String name) {
        if (!args.has(name)) {
            return null;
        }
        String s = args.require(name).replace(' ', 'T');
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (DateTimeParseException e) {
            try {
                return LocalDateTime.parse(s).atZone(ZoneId.systemDefault()).toInstant();
            } catch (DateTimeParseException e2) {
                throw new ToolException("'" + name + "' must be an ISO date-time, e.g. 2024-05-01T10:00:00");
            }
        }
    }
}
