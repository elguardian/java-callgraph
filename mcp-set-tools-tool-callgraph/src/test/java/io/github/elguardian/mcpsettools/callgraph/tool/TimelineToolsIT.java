package io.github.elguardian.mcpsettools.callgraph.tool;

import io.github.elguardian.mcpsettools.testkit.ToolHarness;
import static io.github.elguardian.mcpsettools.testkit.ToolHarness.map;
import static io.github.elguardian.mcpsettools.testkit.ToolHarness.ok;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.elguardian.mcpsettools.testkit.ForkedJvm;
import io.github.elguardian.mcpsettools.testkit.TestCompiler;
import io.github.elguardian.mcpsettools.commons.json.Json;

/** A recording of two threads contending for one lock, drawn in every format. */
class TimelineToolsIT {

    @Test
    void lockContentionFromARecording(@TempDir Path tmp) throws Exception {
        Path agent = Path.of(System.getProperty("mcpsettools.agent.jar"));
        Path app = TestCompiler.create().source("c.Main", """
                package c;
                public class Main {
                    static final Object LOCK = new Object();
                    public static void main(String[] args) throws Exception {
                        Thread a = new Thread(Main::work, "worker-a");
                        Thread b = new Thread(Main::work, "worker-b");
                        a.start();
                        b.start();
                        a.join();
                        b.join();
                    }
                    static void work() {
                        prepare();
                        synchronized (LOCK) {
                            critical();
                        }
                    }
                    static void prepare() { pause(30); }
                    static void critical() { pause(60); }
                    static void pause(long ms) {
                        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    }
                }
                """).compile().writeJar(tmp.resolve("app.jar"));
        Path out = tmp.resolve("rec");
        ForkedJvm.create().classpath(app).jvmArg("-javaagent:" + agent + "=incl=c\\..*;locks=true;jmx=off;out=" + out)
                .mainClass("c.Main").run().assertSuccess();
        ToolHarness tools = new ToolHarness();

        Map<String, Object> m = ok(tools, "timeline", "source", "recording", "trace", List.of(out.toString()), "methods",
                "c.Main.*", "width", 60);
        assertEquals(List.of("main", "worker-a", "worker-b"), ((List<?>) m.get("lanes")).stream()
                .map(x -> map(x).get("thread")).sorted().toList());
        // both threads prepare at the same time; then one waits while the other holds the lock
        assertTrue((Integer) m.get("concurrent_same_block") > 0, m.toString());
        Map<String, Object> lock = map(((List<?>) m.get("locks")).get(0));
        assertTrue(lock.get("lock").toString().startsWith("Object@"), lock.toString());
        assertEquals(2, lock.get("holds"));
        assertTrue((Integer) lock.get("waits") >= 1, lock.toString());
        assertTrue(((String) m.get("diagram")).contains("~"), (String) m.get("diagram"));

        for (String format : List.of("mermaid", "plantuml")) {
            assertTrue(ToolHarness.text(tools, "timeline", "source", "recording", "trace", List.of(out.toString()), "methods",
                    "c.Main.*", "format", format, "diagram_only", true).length() > 50);
        }
        Path chrome = tmp.resolve("trace.json");
        ok(tools, "timeline", "source", "recording", "trace", List.of(out.toString()), "format", "chrome", "output",
                chrome.toString());
        @SuppressWarnings("unchecked")
        Map<String, Object> trace = (Map<String, Object>) Json.parse(Files.readString(chrome));
        List<?> events = (List<?>) trace.get("traceEvents");
        assertTrue(events.stream().anyMatch(e -> "critical".equals(String.valueOf(map(e).get("name")).replaceAll(".*\\.", ""))
                && "X".equals(map(e).get("ph"))), events.toString());
        Path html = tmp.resolve("t.html");
        ok(tools, "timeline", "source", "recording", "trace", List.of(out.toString()), "format", "html", "output",
                html.toString());
        assertTrue(Files.readString(html).contains("<h2>Locks</h2>"));
    }
}
