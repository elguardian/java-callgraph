package io.github.elguardian.mcpsettools.mcp;

import io.github.elguardian.mcpsettools.testkit.ToolHarness;
import io.github.elguardian.mcpsettools.tool.ToolRegistry;
import static io.github.elguardian.mcpsettools.testkit.ToolHarness.error;
import static io.github.elguardian.mcpsettools.testkit.ToolHarness.items;
import static io.github.elguardian.mcpsettools.testkit.ToolHarness.map;
import static io.github.elguardian.mcpsettools.testkit.ToolHarness.ok;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.elguardian.mcpsettools.testkit.TestCompiler;

/** Attach the agent to a running JVM, record through the MBean, and load the recording. */
class LiveToolsIT {

    @Test
    void attachRecordAndLoad(@TempDir Path tmp) throws Exception {
        Path agent = Path.of(System.getProperty("mcpsettools.agent.jar"));
        assertTrue(Files.isRegularFile(agent), "agent jar not built: " + agent);
        Path app = TestCompiler.create().source("l.Loop", """
                package l;
                public class Loop {
                    public static void main(String[] args) throws Exception {
                        System.out.println("ready");
                        System.out.flush();
                        while (true) {
                            Work.tick();
                            Thread.sleep(5);
                        }
                    }
                }
                class Work {
                    static int n;
                    static void tick() { n++; helper(); }
                    static void helper() { }
                }
                """).compile().writeJar(tmp.resolve("loop.jar"));
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process target = new ProcessBuilder(java, "-cp", app.toString(), "l.Loop")
                .redirectError(tmp.resolve("target.err").toFile()).start();
        try {
            BufferedReader out = new BufferedReader(new InputStreamReader(target.getInputStream(),
                    StandardCharsets.UTF_8));
            assertEquals("ready", out.readLine());
            String pid = Long.toString(target.pid());
            ToolHarness tools = new ToolHarness();

            List<?> jvms = (List<?>) ok(tools, "list_jvms", "match", "l.Loop").get("jvms");
            assertEquals(1, jvms.size(), jvms.toString());
            assertEquals(pid, map(jvms.get(0)).get("pid"));
            assertEquals(false, map(jvms.get(0)).get("mcp_set_tools_agent"));

            Path recordings = tmp.resolve("rec");
            String options = "incl=l\\..*;record=manual;jmx=0;out=" + recordings;
            ok(tools, "attach_agent", "pid", pid, "options", options, "agent_jar", agent.toString());
            assertTrue(error(tools, "attach_agent", "pid", pid, "options", options, "agent_jar", agent.toString())
                    .contains("already loaded"));

            Map<String, Object> status = awaitRecorder(tools, pid);
            assertEquals(false, status.get("recording"));

            Map<String, Object> started = ok(tools, "recorder", "pid", pid, "action", "start", "label", "live");
            assertEquals(true, started.get("recording"));
            Thread.sleep(300);
            Map<String, Object> stopped = ok(tools, "recorder", "pid", pid, "action", "stop");
            String dir = (String) stopped.get("stopped");
            assertNotNull(dir, stopped.toString());
            assertEquals(false, stopped.get("recording"));

            Map<String, Object> model = ok(tools, "load_model", "app", List.of(app.toString()), "trace",
                    List.of(dir));
            Map<String, Object> rec = map(((List<?>) map(model.get("runtime")).get("recordings")).get(0));
            assertEquals("live", rec.get("label"));
            assertEquals(true, rec.get("complete"));

            Map<String, Object> tick = ok(tools, "describe_method", "method", "l.Work.tick");
            assertEquals(true, tick.get("executed"));
            assertTrue(((Number) map(tick.get("runtime")).get("calls")).longValue() > 1, tick.toString());
            Map<String, Object> edge = items(map(tick.get("callees"))).get(0);
            assertEquals("l.Work.helper()", edge.get("callee"));
            assertEquals("static+runtime", edge.get("provenance"));

            List<Map<String, Object>> hot = items(ok(tools, "hotspots", "sort", "calls"));
            assertTrue(hot.stream().anyMatch(h -> h.get("method").equals("l.Work.helper()")), hot.toString());

            // main() was already running when the agent attached: it stays uninstrumented
            Map<String, Object> gaps = ok(tools, "gap_report");
            assertTrue(items(map(gaps.get("never_executed"))).contains("l.Loop.main(java.lang.String[])"),
                    gaps.toString());
        } finally {
            target.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
        }
    }

    /** The agent registers its MBean from a background thread right after loading. */
    private static Map<String, Object> awaitRecorder(ToolHarness tools, String pid) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            ToolRegistry.Result r = tools.call("recorder", ToolHarness.args("pid", pid, "action", "status"));
            if (!r.isError()) {
                return map(r.value());
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError(r.error());
            }
            Thread.sleep(100);
        }
    }
}
