package io.github.elguardian.mcpsettools.jvm.tool;

import io.github.elguardian.mcpsettools.tool.Tool;
import io.github.elguardian.mcpsettools.tool.Schema;
import io.github.elguardian.mcpsettools.tool.ToolArgs;
import io.github.elguardian.mcpsettools.tool.ToolException;
import io.github.elguardian.mcpsettools.tool.Results;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.elguardian.mcpsettools.jvm.Jcmd;
import io.github.elguardian.mcpsettools.commons.stack.StackTrace;
import io.github.elguardian.mcpsettools.jvm.threads.ThreadDump;
import io.github.elguardian.mcpsettools.jvm.threads.ThreadDumpAnalysis;
import io.github.elguardian.mcpsettools.jvm.threads.ThreadDumpParser;

/** Thread dump analysis: deadlocks, lock owners, groups of threads, stuck and hot threads. */
final class ThreadTools {

    List<Tool> tools() {
        return List.of(analyze());
    }

    private Tool analyze() {
        return new Tool("thread_dump_analyze", "Analyze thread dumps", """
                Analyzes Java thread dumps (jstack, jcmd Thread.print, kill -3 output; JDK 8 to 25; several \
                dumps per file; dumps inside console or server logs), or takes them from a running local JVM \
                by pid. Reports: deadlocks (cycles of threads waiting for locks the next one holds, monitors \
                and java.util.concurrent locks), contention (each lock with waiters, who holds it and what the \
                holder is doing), groups of threads with the same state and top frames (a hundred request \
                threads in the same socket read), thread pools with their states, and, with two or more dumps, \
                threads stuck at the same place in every dump and the threads that used the most CPU between \
                the first and last dump. Idle threads (pool workers waiting for tasks, selectors) are left out \
                of the groups unless include_idle.""", true,
                Schema.object().strings("files", "Thread dump files, or logs containing dumps", false)
                        .string("text", "Thread dump text, when there is no file", false)
                        .string("pid", "Take the dumps from this local JVM with jcmd Thread.print -l", false)
                        .integer("count", "With pid: dumps to take", 3)
                        .integer("interval_seconds", "With pid: seconds between dumps", 5)
                        .integer("group_frames", "Top frames that make threads 'the same'", 6)
                        .bool("include_idle", "Also list groups of idle threads", false)
                        .strings("app_packages", "Application package prefixes, e.g. org.acme: helps tell idle "
                                + "from busy threads", false)
                        .integer("limit", "Maximum groups, contended locks and threads per list", 15)
                        .tableFormat().build(),
                args -> {
                    List<ThreadDump> dumps = new ArrayList<>();
                    Map<String, Object> m = new LinkedHashMap<>();
                    if (args.has("pid")) {
                        Path file = take(args.require("pid"), args.integer("count", 3), args.integer("interval_seconds", 5));
                        m.put("dump_file", file.toString());
                        dumps.addAll(ThreadDumpParser.parse(file.getFileName().toString(), Files.readString(file)));
                    } else if (args.has("files")) {
                        for (Path f : args.paths("files")) {
                            if (!Files.isRegularFile(f)) {
                                throw new ToolException("Not a file: " + f);
                            }
                            dumps.addAll(ThreadDumpParser.parse(f.getFileName().toString(), Files.readString(f)));
                        }
                    } else if (args.has("text")) {
                        dumps.addAll(ThreadDumpParser.parse("text", args.require("text")));
                    } else {
                        throw new ToolException("Give 'files', 'text' or 'pid'");
                    }
                    if (dumps.isEmpty()) {
                        throw new ToolException("No thread dump found (looked for 'Full thread dump' headers)");
                    }
                    return view(new ThreadDumpAnalysis(dumps, args.strings("app_packages")), args, m);
                });
    }

    private static Path take(String pid, int count, int interval) throws Exception {
        if (count < 1 || count > 20) {
            throw new ToolException("'count' must be 1 to 20");
        }
        StringBuilder all = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                Thread.sleep(Math.max(0, interval) * 1000L);
            }
            try {
                all.append(Jcmd.run(pid, Duration.ofSeconds(60), "Thread.print", "-l")).append('\n');
            } catch (java.io.IOException | IllegalArgumentException | IllegalStateException e) {
                throw new ToolException(e.getMessage());
            }
        }
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "mcp-set-tools-mcp", "threads");
        Files.createDirectories(dir);
        Path file = dir.resolve("threads-" + pid + "-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".txt");
        Files.writeString(file, all);
        return file;
    }

    private static Map<String, Object> view(ThreadDumpAnalysis a, ToolArgs args, Map<String, Object> m) {
        int limit = args.integer("limit", 15);
        int depth = args.integer("group_frames", 6);
        ThreadDump last = a.last();
        m.put("dumps", a.dumps().size());
        List<String> stamps = a.dumps().stream().map(d -> d.timestamp() == null ? d.source() + "#" + (d.index() + 1)
                : d.timestamp()).toList();
        m.put("taken", stamps);
        if (last.jvm() != null) {
            m.put("jvm", last.jvm());
        }
        m.put("threads", last.threads().size());
        m.put("states", a.states());
        List<ThreadDumpAnalysis.Deadlock> deadlocks = a.deadlocks();
        if (!deadlocks.isEmpty()) {
            m.put("deadlocks", deadlocks.stream().map(d -> {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("threads", d.threads());
                if (!d.locks().isEmpty()) {
                    r.put("waiting_for", d.locks());
                }
                r.put("reported_by_jvm", d.reportedByJvm());
                Map<String, Object> stacks = new LinkedHashMap<>();
                d.threads().forEach(t -> {
                    var info = last.thread(t);
                    if (info != null) {
                        stacks.put(t, frames(info.frames(), 4));
                    }
                });
                r.put("stacks", stacks);
                return r;
            }).toList());
        }
        List<ThreadDumpAnalysis.Contention> contention = a.contention();
        if (!contention.isEmpty()) {
            m.put("contention", contention.stream().limit(limit).map(c -> {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("lock", c.lock());
                r.put("waiters", c.waiters().size());
                r.put("owner", c.owner() == null ? "(none: a condition, latch or queue)" : c.owner());
                if (c.owner() != null) {
                    r.put("owner_state", c.ownerState());
                    r.put("owner_doing", frames(c.ownerFrames(), 6));
                    if (c.ownerAlsoWaits()) {
                        r.put("owner_also_waits", true);
                    }
                }
                r.put("waiting_threads", c.waiters().stream().limit(10).toList());
                return r;
            }).toList());
        }
        List<ThreadDumpAnalysis.Group> groups = a.groups(depth);
        boolean idle = args.bool("include_idle", false);
        List<Map<String, Object>> rows = new ArrayList<>();
        List<Map<String, Object>> tableRows = new ArrayList<>();
        int idleThreads = 0;
        for (ThreadDumpAnalysis.Group g : groups) {
            if (g.idle()) {
                idleThreads += g.threads().size();
                if (!idle) {
                    continue;
                }
            }
            if (rows.size() >= limit) {
                continue;
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("count", g.threads().size());
            r.put("state", g.state());
            if (g.idle()) {
                r.put("idle", true);
            }
            r.put("frames", frames(g.frames(), depth));
            r.put("threads", g.threads().stream().limit(5).toList());
            rows.add(r);
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("count", g.threads().size());
            t.put("state", g.state());
            t.put("top frame", g.frames().isEmpty() ? "" : g.frames().get(0).location());
            t.put("example", g.threads().get(0));
            tableRows.add(t);
        }
        m.put("groups", rows);
        m.put("idle_threads", idleThreads);
        if (a.dumps().size() > 1) {
            m.put("stuck", a.stuck(depth).stream().limit(limit).map(s -> {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("thread", s.thread());
                r.put("state", s.state());
                r.put("frames", frames(s.frames(), 4));
                return r;
            }).toList());
            List<ThreadDumpAnalysis.Hot> hot = a.hot(limit);
            if (!hot.isEmpty()) {
                m.put("hot", hot.stream().map(h -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("thread", h.thread());
                    r.put("cpu_ms", Math.round(h.cpuMillis() * 10) / 10.0);
                    if (h.percent() >= 0) {
                        r.put("cpu_percent", Math.round(h.percent()));
                    }
                    r.put("state", h.state());
                    if (h.top() != null) {
                        r.put("top_frame", h.top().toString());
                    }
                    return r;
                }).toList());
            }
        }
        m.put("pools", a.pools().stream().filter(p -> p.threads() > 1).limit(limit).map(p -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("pool", p.name());
            r.put("threads", p.threads());
            r.put("states", p.states());
            return r;
        }).toList());
        Results.table(m, tableRows, null, args);
        return m;
    }

    private static List<String> frames(List<StackTrace.Frame> frames, int n) {
        return frames.stream().limit(n).map(StackTrace.Frame::toString).toList();
    }
}
