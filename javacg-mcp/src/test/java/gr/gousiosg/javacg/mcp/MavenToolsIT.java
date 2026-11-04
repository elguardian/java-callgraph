package gr.gousiosg.javacg.mcp;

import static gr.gousiosg.javacg.mcp.Fixture.map;
import static gr.gousiosg.javacg.mcp.Fixture.ok;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs the Maven found on this machine offline on a generated project (plugins come from the local repository). */
class MavenToolsIT {

    @Test
    void runReportsTestFailuresThenCompileErrors(@TempDir Path tmp) throws Exception {
        Path dir = MavenProject.create(tmp.resolve("p"));
        JavacgTools tools = new JavacgTools();

        Map<String, Object> tests = ok(tools, "maven_run", "project_dir", dir.toString(), "offline", true, "args",
                List.of("-fae", "verify"), "threads", "2", "timeout_seconds", 300);
        assertEquals(1, tests.get("exit_code"), tests.toString());
        assertEquals("failure", tests.get("status"));
        List<?> modules = (List<?>) tests.get("modules");
        assertEquals(List.of("success", "success", "failure", "skipped"), modules.stream().map(x -> map(x).get("status")).toList());
        assertEquals("mvn-builder-b", map(modules.get(2)).get("thread"));
        List<?> failures = (List<?>) tests.get("test_failures");
        assertEquals(List.of("b.BTest.npe", "b.BTest.five"), failures.stream().map(x -> map(x).get("test")).toList());
        assertTrue(((String) map(failures.get(1)).get("exception")).contains("expected: <5> but was: <4>"));
        assertEquals(List.of(dir.resolve("b/target/surefire-reports").toString()), tests.get("test_reports"));
        assertEquals("-rf :b", tests.get("resume_with"));

        Map<String, Object> compile = ok(tools, "maven_run", "project_dir", dir.toString(), "offline", true, "args",
                List.of("-DskipTests", "verify"));
        Map<String, Object> err = map(((List<?>) compile.get("compiler_errors")).get(0));
        assertEquals("c/src/main/java/c/C.java", err.get("file"));
        assertEquals(1, err.get("line"));
        assertEquals(50, err.get("column"));
        assertTrue(((String) err.get("message")).startsWith("cannot find symbol\nsymbol:   method missing()"));
        assertEquals("c", err.get("module"));

        // the junit tools read the reports this build left
        Map<String, Object> junit = ok(tools, "junit_failures", "paths", tests.get("test_reports"));
        assertEquals(2, junit.get("total"));
    }
}
