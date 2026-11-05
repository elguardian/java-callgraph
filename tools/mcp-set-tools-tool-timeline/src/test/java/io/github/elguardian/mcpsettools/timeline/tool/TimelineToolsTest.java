package io.github.elguardian.mcpsettools.timeline.tool;

import io.github.elguardian.mcpsettools.testkit.ToolHarness;
import static io.github.elguardian.mcpsettools.testkit.ToolHarness.error;
import static io.github.elguardian.mcpsettools.testkit.ToolHarness.map;
import static io.github.elguardian.mcpsettools.testkit.ToolHarness.ok;
import static io.github.elguardian.mcpsettools.testkit.ToolHarness.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TimelineToolsTest {

    @TempDir
    Path tmp;

    ToolHarness tools = new ToolHarness();

    @Test
    void events() {
        List<Map<String, Object>> events = List.of(
                Map.of("thread", "t1", "start", 0, "end", 100, "label", "load"),
                Map.of("thread", "t2", "start", 50, "end", 150, "label", "load"),
                Map.of("thread", "t2", "start", "1970-01-01T00:00:00.200Z", "end", 300, "label", "save"));
        Map<String, Object> m = ok(tools, "timeline", "source", "events", "events", events, "width", 30, "zone", "UTC");
        assertEquals(2, m.get("max_concurrency"));
        assertEquals(1, m.get("concurrent_same_block"));
        assertEquals("300ms", m.get("duration"));
        assertTrue(((String) m.get("diagram")).contains("\nt1     AAAAAAAAAA\nt2          AAAAAAAAAA     BBBBBBBBBB\n"), (String) m.get("diagram"));
        String mermaid = text(tools, "timeline", "source", "events", "events", events, "format", "mermaid", "diagram_only", true);
        assertTrue(mermaid.startsWith("gantt\n"));
        assertTrue(error(tools, "timeline", "source", "events", "events", events, "format", "html").contains("give 'output'"));
        assertTrue(error(tools, "timeline", "source", "events", "events", events, "from_ms", 400)
                .startsWith("Nothing left to draw"));
    }


}
