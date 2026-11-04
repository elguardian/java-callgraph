package gr.gousiosg.javacg.mcp;

import static gr.gousiosg.javacg.mcp.Fixture.error;
import static gr.gousiosg.javacg.mcp.Fixture.map;
import static gr.gousiosg.javacg.mcp.Fixture.ok;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ThreadToolsTest {

    JavacgTools tools = new JavacgTools();

    @Test
    void analyzesDumpFile() throws Exception {
        Path f = Path.of(getClass().getResource("/three-dumps.txt").toURI());
        Map<String, Object> m = ok(tools, "thread_dump_analyze", "files", List.of(f.toString()), "app_packages", List.of("d"),
                "table_format", "ascii");
        assertEquals(3, m.get("dumps"));
        assertEquals(2, ((List<?>) m.get("deadlocks")).size());
        Map<String, Object> c = map(((List<?>) m.get("contention")).get(0));
        assertEquals(3, c.get("waiters"));
        assertEquals("holder", c.get("owner"));
        assertEquals("holder", map(((List<?>) m.get("hot")).get(0)).get("thread"));
        assertTrue(((String) m.get("table")).contains("|     3 | BLOCKED       | d.Stuck.lambda$main$6         | worker-39 |"), (String) m.get("table"));
        assertTrue(error(tools, "thread_dump_analyze", "text", "nothing").startsWith("No thread dump found"));
        assertTrue(error(tools, "thread_dump_analyze").startsWith("Give 'files'"));
    }
}
