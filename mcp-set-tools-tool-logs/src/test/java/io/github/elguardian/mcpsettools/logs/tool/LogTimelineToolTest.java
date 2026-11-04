package io.github.elguardian.mcpsettools.logs.tool;

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

class LogTimelineToolTest {

    @TempDir
    Path tmp;

    ToolHarness tools = new ToolHarness();

    @Test
    void logSpansToHtml() throws Exception {
        Path log = Files.writeString(tmp.resolve("app.log"), """
                10:00:00.000 [http-1] INFO  com.acme.Api - begin request r1
                10:00:00.100 [http-2] INFO  com.acme.Api - begin request r2
                10:00:00.400 [http-2] INFO  com.acme.Api - end request r2
                10:00:00.600 [http-1] INFO  com.acme.Api - end request r1
                """);
        Path out = tmp.resolve("t.html");
        Map<String, Object> m = ok(tools, "timeline", "source", "log", "files", List.of(log.toString()), "spans",
                List.of(Map.of("name", "request", "start", "begin request (?<id>\\w+)", "end", "end request (?<id>\\w+)",
                        "key", "id")), "format", "html", "output", out.toString(), "zone", "UTC");
        assertEquals(2, m.get("spans"));
        assertEquals("10:00:00.000", m.get("start"));
        assertTrue(Files.readString(out).contains("<svg"));
        assertTrue(error(tools, "timeline", "source", "log", "files", List.of(log.toString())).contains("needs 'spans'"));
    }
}
