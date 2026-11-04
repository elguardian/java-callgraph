package io.github.elguardian.mcpsettools.mcp;

import io.github.elguardian.mcpsettools.tool.Tool;
import io.github.elguardian.mcpsettools.tool.Schema;
import io.github.elguardian.mcpsettools.tool.ToolArgs;
import io.github.elguardian.mcpsettools.tool.ToolException;
import io.github.elguardian.mcpsettools.tool.Jobs;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.management.JMX;
import javax.management.MBeanServerConnection;
import javax.management.remote.JMXConnector;

import com.sun.tools.attach.VirtualMachine;

import io.github.elguardian.mcpsettools.rt.RecorderControlMXBean;
import io.github.elguardian.mcpsettools.commons.json.Json;

/** Watching one method of a running JVM for a while: calls, latency percentiles, exceptions, sample values. */
final class WatchTools {

    private final Jobs jobs;

    WatchTools(Jobs jobs) {
        this.jobs = jobs;
    }

    List<Tool> tools() {
        return List.of(watch());
    }

    private Tool watch() {
        return new Tool("watch_method", "Watch a method in a running JVM", """
                Instruments the methods matching 'method' in a running JVM for 'seconds', then removes the \
                instrumentation (the classes get their previous bytecode back; the agent also stops the watch \
                on its own at the deadline). Reports the number of calls, failures, latency (total, max, \
                p50/p90/p99), exceptions with a sample message and stack, the first calls and the slowest calls \
                with their thread, arguments and return value or exception. Loads the mcp-set-tools agent if the JVM \
                does not have it (by pid; with no recording), or uses the agent already there (needs its JMX \
                bean: jmx not off). Arguments and results are shown with toString() (capture=tostring), which \
                runs the target's own code; capture=types or none avoids that. JDK and mcp-set-tools classes cannot be \
                watched.""", false,
                Schema.object().string("method", "pkg.Class.method with * wildcards, optionally with parameter types "
                        + "'(int,java.lang.String)' or a JVM descriptor prefix '(ILjava/lang/String;)', e.g. "
                        + "'com.acme.*Dao.save*'", true)
                        .string("pid", "Local JVM", false)
                        .string("jmx_url", "Remote JVM with the agent loaded: JMX service URL or host:port", false)
                        .string("username", "JMX user for jmx_url", false)
                        .string("password", "JMX password for jmx_url", false)
                        .integer("seconds", "How long to watch (1 to 600)", 20)
                        .integer("samples", "Calls recorded with their values", 20)
                        .choice("capture", "How values are shown in samples", "tostring", "tostring", "types", "none")
                        .raw("min_duration_ms", "Only calls at least this long become samples", false,
                                Map.of("type", "number", "default", 0))
                        .bool("only_exceptions", "Only calls that threw become samples", false)
                        .string("agent_jar", "Agent jar to load when the JVM has none (default: the embedded one)", false)
                        .bool("background", "Return a job_id at once and watch in the background", false).build(),
                args -> {
                    int seconds = args.integer("seconds", 20);
                    if (seconds < 1 || seconds > 600) {
                        throw new ToolException("'seconds' must be 1 to 600");
                    }
                    String id = start(args, seconds);
                    if (args.bool("background", false)) {
                        Jobs.Job job = jobs.start("watch_method", j -> {
                            j.progress("watching " + args.require("method") + " for " + seconds + " s (watch " + id + ")");
                            sleep(seconds, j);
                            return finish(args, id);
                        });
                        return Jobs.started(job, "The watch");
                    }
                    sleep(seconds, null);
                    return finish(args, id);
                });
    }

    /** Loads the agent if needed, starts the watch and returns its id. */
    private String start(ToolArgs args, int seconds) throws Exception {
        if (args.has("pid")) {
            ensureAgent(args);
        }
        StringBuilder spec = new StringBuilder("target=").append(args.require("method"));
        spec.append(";seconds=").append(seconds + 5);
        spec.append(";samples=").append(Math.max(0, args.integer("samples", 20)));
        spec.append(";capture=").append(args.string("capture", "tostring"));
        if (args.raw("min_duration_ms") instanceof Number n) {
            spec.append(";min_us=").append(Math.round(n.doubleValue() * 1000));
        }
        spec.append(";only_exceptions=").append(args.bool("only_exceptions", false));
        Map<String, Object> r = call(args, rec -> rec.watch(spec.toString()));
        if (r.get("error") != null) {
            throw new ToolException("The watch did not start: " + r.get("error"));
        }
        return String.valueOf(r.get("id"));
    }

    private Map<String, Object> finish(ToolArgs args, String id) throws Exception {
        Map<String, Object> raw = call(args, rec -> rec.watchResult(id, true));
        if (raw.get("error") != null) {
            throw new ToolException(String.valueOf(raw.get("error")));
        }
        return view(raw);
    }

    /** Loads the agent without recording anything when the JVM has none, and waits for its MBean. */
    private static void ensureAgent(ToolArgs args) throws Exception {
        String pid = args.require("pid");
        VirtualMachine vm = LiveTools.attach(pid);
        try {
            String loaded = vm.getSystemProperties().getProperty(LiveTools.AGENT_LOADED_PROPERTY);
            if (loaded == null) {
                vm.loadAgent(LiveTools.agentJar(args).toString(), "record=manual;jmx=0");
            } else if (loaded.contains("jmx=off")) {
                throw new ToolException("The mcp-set-tools agent in " + pid + " runs with jmx=off: watches need its MBean");
            }
        } finally {
            vm.detach();
        }
    }

    @FunctionalInterface
    private interface Call {
        String apply(RecorderControlMXBean rec) throws Exception;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> call(ToolArgs args, Call call) throws Exception {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (true) {
            try (JMXConnector connector = LiveTools.connect(args)) {
                MBeanServerConnection conn = connector.getMBeanServerConnection();
                if (conn.isRegistered(LiveTools.recorderName())) {
                    RecorderControlMXBean rec = JMX.newMXBeanProxy(conn, LiveTools.recorderName(), RecorderControlMXBean.class);
                    String json = call.apply(rec);
                    return Json.object(json);
                }
            }
            if (System.nanoTime() > deadline) {
                throw new ToolException("No mcp-set-tools:type=Recorder MBean in the target: the agent is not loaded, runs with "
                        + "jmx=off, or is still in its jmx delay (20 s on WildFly with jmx=auto)");
            }
            Thread.sleep(200);
        }
    }

    private static void sleep(int seconds, Jobs.Job job) throws InterruptedException {
        long end = System.nanoTime() + seconds * 1_000_000_000L;
        while (System.nanoTime() < end) {
            if (job != null && job.cancelled()) {
                throw new InterruptedException();
            }
            Thread.sleep(Math.min(500, Math.max(1, (end - System.nanoTime()) / 1_000_000)));
        }
    }

    private static Map<String, Object> view(Map<String, Object> raw) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("method", raw.get("target"));
        m.put("watched_methods", raw.get("methods"));
        long started = num(raw.get("started_ms"));
        long ended = num(raw.get("ended_ms"));
        double secs = Math.max(0.001, (ended - started) / 1000.0);
        long calls = num(raw.get("calls"));
        m.put("seconds", Math.round(secs * 10) / 10.0);
        m.put("calls", calls);
        m.put("calls_per_second", Math.round(calls / secs * 10) / 10.0);
        m.put("failed", num(raw.get("failed")));
        m.put("total_ms", ms(raw.get("total_us")));
        if (calls > 0) {
            m.put("mean_ms", Math.round(num(raw.get("total_us")) / (double) calls) / 1000.0);
        }
        m.put("p50_ms", ms(raw.get("p50_us")));
        m.put("p90_ms", ms(raw.get("p90_us")));
        m.put("p99_ms", ms(raw.get("p99_us")));
        m.put("max_ms", ms(raw.get("max_us")));
        if (raw.get("exceptions") instanceof List<?> ex && !ex.isEmpty()) {
            m.put("exceptions", ex);
        }
        m.put("samples", calls(raw.get("samples")));
        m.put("slowest", calls(raw.get("slowest")));
        if (calls == 0) {
            m.put("note", "No call during the watch: is the method on the code path being exercised?");
        }
        return m;
    }

    private static List<Map<String, Object>> calls(Object o) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (o instanceof List<?> l) {
            for (Object x : l) {
                if (x instanceof Map<?, ?> s) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("thread", s.get("thread"));
                    r.put("at", Instant.ofEpochMilli(num(s.get("at_ms"))).toString());
                    r.put("ms", ms(s.get("duration_us")));
                    if (s.get("args") != null) {
                        r.put("args", s.get("args"));
                    }
                    if (s.get("returned") != null) {
                        r.put("returned", s.get("returned"));
                    }
                    if (s.get("threw") != null) {
                        r.put("threw", s.get("threw"));
                    }
                    out.add(r);
                }
            }
        }
        return out;
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0;
    }

    private static double ms(Object us) {
        return num(us) / 1000.0;
    }
}
