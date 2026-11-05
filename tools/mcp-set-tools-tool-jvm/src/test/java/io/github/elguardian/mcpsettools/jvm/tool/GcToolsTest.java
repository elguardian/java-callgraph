package io.github.elguardian.mcpsettools.jvm.tool;

import io.github.elguardian.mcpsettools.testkit.ToolHarness;
import static io.github.elguardian.mcpsettools.testkit.ToolHarness.error;
import static io.github.elguardian.mcpsettools.testkit.ToolHarness.map;
import static io.github.elguardian.mcpsettools.testkit.ToolHarness.ok;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class GcToolsTest {

    ToolHarness tools = new ToolHarness();

    static String resource(String name) throws Exception {
        return Path.of(GcToolsTest.class.getResource("/" + name).toURI()).toString();
    }

    @Test
    void gcLog() throws Exception {
        Map<String, Object> m = ok(tools, "gc_log_analyze", "files", List.of(resource("gc-g1.log")), "table_format", "markdown");
        assertEquals("G1", m.get("collector"));
        assertEquals(true, map(m.get("heap_after_gc")).get("leak_suspected"));
        assertEquals(11, m.get("full_gcs"));
        assertTrue(((String) m.get("table")).startsWith("| kind | count | total_ms |"), (String) m.get("table"));
        Map<String, Object> t = ok(tools, "timeline", "source", "gc", "gc_files", List.of(resource("gc-g1.log")), "width", 40);
        assertTrue(((List<?>) t.get("lanes")).stream().anyMatch(l -> map(l).get("thread").equals("GC pauses")));
        assertTrue(error(tools, "gc_log_analyze", "files", List.of(resource("histograms.txt"))).startsWith("No GC events"));
    }

    @Test
    void histograms() throws Exception {
        Map<String, Object> m = ok(tools, "heap_histogram", "files", List.of(resource("histograms.txt")));
        assertEquals(3, m.get("snapshots"));
        assertEquals("[B", ((List<?>) m.get("leak_candidates")).get(0));
        assertEquals(true, map(((List<?>) m.get("growth")).get(0)).get("growing_in_every_snapshot"));
    }
}
