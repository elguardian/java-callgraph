package io.github.elguardian.mcpsettools.jvm.tool;

import io.github.elguardian.mcpsettools.testkit.ToolHarness;
import static io.github.elguardian.mcpsettools.testkit.ToolHarness.error;
import static io.github.elguardian.mcpsettools.testkit.ToolHarness.map;
import static io.github.elguardian.mcpsettools.testkit.ToolHarness.ok;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.elguardian.mcpsettools.testkit.TestCompiler;

/** watch_method on running JVMs, with and without the agent already loaded. */
class WatchToolsIT {

    static final String APP = """
            package l;
            public class Loop {
                public static void main(String[] args) throws Exception {
                    System.out.println("ready");
                    System.out.flush();
                    Work w = new Work();
                    for (int i = 0; ; i++) {
                        try { w.tick(i, "order-" + i); } catch (IllegalStateException e) { }
                        w.other();
                        Thread.sleep(2);
                    }
                }
            }
            class Work {
                int tick(int n, String id) {
                    if (n % 10 == 9) { throw new IllegalStateException("bad " + id); }
                    return id.length() + n;
                }
                void other() { }
            }
            """;

    private static Process start(Path app, String... jvmArgs) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> cmd = new ArrayList<>(List.of(java));
        cmd.addAll(List.of(jvmArgs));
        cmd.addAll(List.of("-cp", app.toString(), "l.Loop"));
        Process p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        BufferedReader out = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
        assertEquals("ready", out.readLine());
        return p;
    }

    @Test
    void attachesAndWatches(@TempDir Path tmp) throws Exception {
        Path app = TestCompiler.create().source("l.Loop", APP).compile().writeJar(tmp.resolve("loop.jar"));
        Process p = start(app);
        try {
            ToolHarness tools = new ToolHarness();
            String pid = Long.toString(p.pid());
            Map<String, Object> m = ok(tools, "watch_method", "pid", pid, "method", "l.Work.tick", "seconds", 2, "samples", 3);
            assertTrue((Long) m.get("calls") > 50, m.toString());
            assertTrue((Long) m.get("failed") > 0, m.toString());
            assertEquals(List.of("l.Work.tick(ILjava/lang/String;)I"), m.get("watched_methods"));
            Map<String, Object> first = map(((List<?>) m.get("samples")).get(0));
            assertEquals("main", first.get("thread"));
            assertTrue(((List<?>) first.get("args")).get(1).toString().startsWith("order-"), first.toString());
            Map<String, Object> ex = map(((List<?>) m.get("exceptions")).get(0));
            assertEquals("java.lang.IllegalStateException", ex.get("type"));
            assertTrue(((Number) m.get("p99_ms")).doubleValue() >= 0);

            // the watch is gone: a new one on another method still works, and tick is no longer counted
            Map<String, Object> other = ok(tools, "watch_method", "pid", pid, "method", "l.Work.oth*", "seconds", 1,
                    "capture", "none");
            assertTrue((Long) other.get("calls") > 20, other.toString());
            assertEquals(List.of("l.Work.other()V"), other.get("watched_methods"));
            assertTrue(error(tools, "watch_method", "pid", pid, "method", "l.Missing.run", "seconds", 1)
                    .contains("no loaded class matches"));
            assertTrue(error(tools, "watch_method", "pid", pid, "method", "java.util.HashMap.get", "seconds", 1)
                    .contains("not allowed"));
        } finally {
            p.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void usesTheAgentAlreadyLoadedInTheBackground(@TempDir Path tmp) throws Exception {
        Path app = TestCompiler.create().source("l.Loop", APP).compile().writeJar(tmp.resolve("loop.jar"));
        String agent = System.getProperty("mcpsettools.agent.jar");
        Process p = start(app, "-javaagent:" + agent + "=incl=l\\..*;record=manual;jmx=0;out=" + tmp.resolve("rec"));
        try {
            ToolHarness tools = new ToolHarness();
            Map<String, Object> started = ok(tools, "watch_method", "pid", Long.toString(p.pid()), "method", "l.Work.tick(int,java.lang.String)",
                    "seconds", 1, "background", true, "only_exceptions", true);
            Map<String, Object> status = ok(tools, "job_status", "job_id", started.get("job_id"), "wait_seconds", 30);
            assertEquals("done", status.get("status"), status.toString());
            Map<String, Object> r = map(status.get("result"));
            assertTrue((Long) r.get("calls") > 20, r.toString());
            List<?> samples = (List<?>) r.get("samples");
            assertTrue(!samples.isEmpty() && samples.stream().allMatch(s -> map(s).containsKey("threw")), samples.toString());
        } finally {
            p.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
        }
    }
}
