package gr.gousiosg.javacg.mcp;

import static gr.gousiosg.javacg.mcp.Fixture.error;
import static gr.gousiosg.javacg.mcp.Fixture.map;
import static gr.gousiosg.javacg.mcp.Fixture.ok;
import static gr.gousiosg.javacg.mcp.Fixture.text;
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

    JavacgTools tools = new JavacgTools();

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

    @Test
    void mavenBuild() throws Exception {
        Path log = Path.of(getClass().getResource("/maven-T.log").toURI());
        Map<String, Object> m = ok(tools, "timeline", "source", "maven", "log_file", log.toString(), "format", "plantuml");
        assertEquals(3, m.get("spans"));
        assertTrue(((String) m.get("diagram")).contains("concise \"mvn-builder-a\""), (String) m.get("diagram"));
        assertEquals(List.of("mvn-builder-root", "mvn-builder-a", "mvn-builder-b"),
                ((List<?>) m.get("lanes")).stream().map(x -> map(x).get("thread")).toList());
    }
}
