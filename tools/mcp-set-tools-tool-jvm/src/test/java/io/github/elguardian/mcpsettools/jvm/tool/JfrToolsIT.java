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
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.elguardian.mcpsettools.testkit.TestCompiler;

/** Records a busy JVM by pid, in the foreground and as a background job. */
class JfrToolsIT {

    @Test
    void recordsALiveJvm(@TempDir Path tmp) throws Exception {
        Path app = TestCompiler.create().source("h.Hot", """
                package h;
                public class Hot {
                    static volatile long sink;
                    public static void main(String[] args) {
                        System.out.println("ready");
                        System.out.flush();
                        while (true) { sink += crunch(); }
                    }
                    static long crunch() { long x = 0; for (int i = 0; i < 1_000_000; i++) { x += i ^ (x << 1); } return x; }
                }
                """).compile().writeJar(tmp.resolve("hot.jar"));
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process p = new ProcessBuilder(java, "-cp", app.toString(), "h.Hot").redirectErrorStream(true).start();
        try {
            BufferedReader out = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            assertEquals("ready", out.readLine());
            ToolHarness tools = new ToolHarness();
            String pid = Long.toString(p.pid());
            Map<String, Object> m = ok(tools, "jfr_analyze", "pid", pid, "seconds", 2, "sections", List.of("cpu"),
                    "app_packages", List.of("h"));
            Map<String, Object> cpu = map(m.get("cpu"));
            assertEquals("h.Hot.crunch", map(((List<?>) cpu.get("self")).get(0)).get("method"));
            assertTrue(((String) map(((List<?>) cpu.get("first_app_frame")).get(0)).get("frame")).startsWith("h.Hot.crunch"));

            Map<String, Object> started = ok(tools, "jfr_analyze", "pid", pid, "seconds", 1, "background", true,
                    "sections", List.of("gc"));
            String job = (String) started.get("job_id");
            Map<String, Object> status = ok(tools, "job_status", "job_id", job, "wait_seconds", 30);
            assertEquals("done", status.get("status"), status.toString());
            assertTrue(map(status.get("result")).containsKey("gc"));
            assertTrue(error(tools, "job_status", "job_id", "j999").startsWith("Unknown job_id"));
        } finally {
            p.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
        }
    }
}
