package io.github.elguardian.mcpsettools.jvm.tool;

import io.github.elguardian.mcpsettools.testkit.ToolHarness;
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

/** Histograms taken by pid from a JVM that keeps adding to a static list, and its GC log. */
class GcToolsIT {

    @Test
    void leakingJvm(@TempDir Path tmp) throws Exception {
        Path app = TestCompiler.create().source("l.Leak", """
                package l;
                import java.util.ArrayList;
                import java.util.List;
                public class Leak {
                    static final List<Item> ITEMS = new ArrayList<>();
                    record Item(byte[] payload) { }
                    public static void main(String[] args) throws Exception {
                        System.out.println("ready");
                        System.out.flush();
                        while (true) {
                            for (int i = 0; i < 2000; i++) {
                                ITEMS.add(new Item(new byte[512]));
                            }
                            Thread.sleep(100);
                        }
                    }
                }
                """).compile().writeJar(tmp.resolve("leak.jar"));
        Path gcLog = tmp.resolve("gc.log");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process p = new ProcessBuilder(java, "-Xmx256m", "-Xlog:gc:file=" + gcLog, "-cp", app.toString(), "l.Leak")
                .redirectErrorStream(true).start();
        try {
            BufferedReader out = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            assertEquals("ready", out.readLine());
            ToolHarness tools = new ToolHarness();
            Map<String, Object> m = ok(tools, "heap_histogram", "pid", Long.toString(p.pid()), "count", 3, "interval_seconds", 1);
            assertEquals(3, m.get("snapshots"));
            List<?> leaks = (List<?>) m.get("leak_candidates");
            assertTrue(leaks.contains("l.Leak$Item"), leaks.toString());
            // the histograms forced full GCs, which the GC log shows
            Map<String, Object> gc = ok(tools, "gc_log_analyze", "files", List.of(gcLog.toString()));
            assertTrue(((Map<?, ?>) gc.get("full_gc_causes")).containsKey("Heap Inspection Initiated GC"), gc.toString());
        } finally {
            p.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
        }
    }
}
