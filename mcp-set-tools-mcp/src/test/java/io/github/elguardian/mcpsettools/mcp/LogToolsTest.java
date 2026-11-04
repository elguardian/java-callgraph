package io.github.elguardian.mcpsettools.mcp;

import static io.github.elguardian.mcpsettools.mcp.Fixture.error;
import static io.github.elguardian.mcpsettools.mcp.Fixture.map;
import static io.github.elguardian.mcpsettools.mcp.Fixture.ok;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LogToolsTest {

    @TempDir
    Path tmp;

    ServerTools tools = new ServerTools();
    Path log;

    @BeforeEach
    void write() throws Exception {
        log = tmp.resolve("server.log");
        Files.writeString(log, """
                2024-05-01 10:00:00,100 INFO  [org.jboss.as.server] (Controller Boot Thread) WFLYSRV0039: Creating http management service
                2024-05-01 10:00:02,500 ERROR [com.acme.Svc] (default task-1) Order 17 failed
                java.lang.IllegalStateException: cannot save
                \tat com.acme.Svc.save(Svc.java:88)
                2024-05-01 10:00:03,000 ERROR [com.acme.Svc] (default task-2) Order 18 failed
                java.lang.IllegalStateException: cannot save
                \tat com.acme.Svc.save(Svc.java:88)
                2024-05-01 10:05:00,000 INFO  [com.acme.Api] (default task-1) request 42 took 15 ms
                """);
    }

    @SuppressWarnings("unchecked")
    @Test
    void recordsWithAutoDetection() {
        Map<String, Object> m = ok(tools, "log_query", "files", List.of(log.toString()), "zone", "UTC", "min_level",
                "WARN", "table_format", "markdown");
        assertEquals("wildfly", m.get("format"));
        assertEquals(2L, m.get("matched"));
        Map<String, Object> first = ((List<Map<String, Object>>) m.get("records")).get(0);
        assertEquals("line 2", first.get("at"));
        assertEquals("java.lang.IllegalStateException: cannot save (com.acme.Svc.save:88)", first.get("exception"));
        assertTrue(((String) m.get("table")).startsWith("| time | level | thread | logger | message |"));
    }

    @Test
    void groupsErrorsAndHistogram() {
        Map<String, Object> g = ok(tools, "log_query", "files", List.of(log.toString()), "output", "groups");
        assertEquals("Order <*> failed", map(((List<?>) g.get("groups")).get(0)).get("template"));
        Map<String, Object> e = ok(tools, "log_query", "files", List.of(log.toString()), "output", "errors");
        assertEquals(1, ((List<?>) e.get("errors")).size());
        assertEquals(2L, map(((List<?>) e.get("errors")).get(0)).get("count"));
        Map<String, Object> h = ok(tools, "log_query", "files", List.of(log.toString()), "output", "histogram",
                "bucket", "1m", "zone", "UTC");
        List<?> rows = (List<?>) h.get("histogram");
        assertEquals(2, rows.size());
        assertEquals("2024-05-01T10:00:00Z", map(rows.get(0)).get("start"));
        assertEquals(3L, map(rows.get(0)).get("total"));
    }

    @Test
    void patternCheckAndErrors() throws Exception {
        Map<String, Object> p = ok(tools, "log_pattern", "file", log.toString(), "records", 1);
        assertEquals("wildfly", p.get("format"));
        assertEquals(4L, p.get("record_starts"));
        assertEquals(1, ((List<?>) p.get("sample")).size());
        assertTrue(((Map<?, ?>) ok(tools, "log_pattern").get("presets")).containsKey("logback"));
        Path text = Files.writeString(tmp.resolve("x.txt"), "hello\nworld\n");
        assertTrue(error(tools, "log_query", "files", List.of(text.toString())).startsWith("Could not recognize"));
        assertTrue(error(tools, "log_query", "files", List.of(log.toString()), "message", "(").contains("not a valid regex"));
        assertTrue(error(tools, "log_query", "files", List.of(log.toString()), "from", "yesterday").contains("Cannot read time"));
    }
}
