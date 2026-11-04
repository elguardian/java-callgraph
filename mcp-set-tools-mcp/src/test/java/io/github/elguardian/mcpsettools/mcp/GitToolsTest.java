package io.github.elguardian.mcpsettools.mcp;

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
import org.junit.jupiter.api.io.TempDir;

class GitToolsTest {

    static final String SVC = """
            package org.acme;

            public class Svc {
                public void save(String id) {
                    check(id);
                }
                void check(String id) {
                    throw new IllegalStateException(id);
                }
            }
            """;

    static final String TRACE = """
            java.lang.IllegalStateException: 7
            \tat org.acme.Svc.check(Svc.java:8)
            \tat org.acme.Svc.save(Svc.java:5)
            """;

    @Test
    void mapsAndRanksVersions(@TempDir Path tmp) throws Exception {
        TestRepo repo = new TestRepo(tmp.resolve("r"));
        repo.write("src/main/java/org/acme/Svc.java", SVC).commit("1.0");
        repo.tag("1.0");
        repo.write("src/main/java/org/acme/Svc.java", SVC.replace("public class Svc {", "public class Svc {\n    // a\n    // b"))
                .commit("1.1");
        repo.tag("1.1");
        ToolHarness tools = new ToolHarness();
        Map<String, Object> one = ok(tools, "stacktrace_map", "text", TRACE, "repo", repo.dir.toString(), "ref", "1.0");
        assertEquals("Every frame found in the repository fits 1.0", one.get("verdict"));
        Map<String, Object> f = map(((List<?>) one.get("frames")).get(0));
        assertEquals("src/main/java/org/acme/Svc.java:8", f.get("path"));
        assertTrue(((String) f.get("source")).contains(">    8          throw new IllegalStateException(id);"));

        Map<String, Object> ranked = ok(tools, "stacktrace_map", "text", TRACE, "repo", repo.dir.toString(), "tag_pattern", "1.*");
        assertEquals("1.0", ranked.get("best"));
        assertEquals(List.of("1.0", "1.1"), ((List<?>) ranked.get("candidates")).stream().map(c -> map(c).get("ref")).toList());
        assertTrue(error(tools, "stacktrace_map", "text", TRACE, "repo", tmp.toString()).startsWith("Not a git repository"));
        assertTrue(error(tools, "stacktrace_map", "text", "no trace", "repo", repo.dir.toString()).startsWith("No stack trace"));
    }

    @Test
    void bisectWithACommand(@TempDir Path tmp) throws Exception {
        TestRepo repo = new TestRepo(tmp.resolve("b"));
        String first = repo.write("f.txt", "ok\n").commit("1");
        repo.write("f.txt", "ok 2\n").commit("2");
        String broken = repo.write("f.txt", "BROKEN\n").commit("3");
        repo.write("f.txt", "BROKEN 4\n").commit("4");
        ToolHarness tools = new ToolHarness();
        String job = (String) ok(tools, "bisect_run", "project_dir", repo.dir.toString(), "good", first, "oracle", "command",
                "command", List.of("sh", "-c", "! grep -q BROKEN f.txt")).get("job_id");
        Map<String, Object> status = ok(tools, "job_status", "job_id", job, "wait_seconds", 60);
        assertEquals(broken, map(map(status.get("result")).get("first_bad")).get("commit"), status.toString());
        assertTrue(((List<?>) status.get("progress")).get(0).toString().startsWith("bisecting "));
        assertTrue(error(tools, "bisect_run", "project_dir", repo.dir.toString(), "good", "nope", "oracle", "command",
                "command", List.of("true")).contains("failed"));
        assertTrue(error(tools, "bisect_run", "project_dir", repo.dir.toString(), "good", first).contains("needs 'args'"));
    }
}
