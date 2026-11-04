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
import org.junit.jupiter.api.io.TempDir;

class MavenToolsTest {

    @TempDir
    Path tmp;

    JavacgTools tools = new JavacgTools();

    @Test
    void reactorSelectionAndChangedFiles() throws Exception {
        Path dir = MavenProject.create(tmp);
        Map<String, Object> m = ok(tools, "maven_reactor", "project_dir", dir.toString(), "projects", List.of("b"),
                "also_make", true, "changed_files", List.of("a/src/main/java/a/A.java", "README.md"), "table_format", "ascii");
        assertEquals(List.of("root", "a", "b", "c"), ((List<?>) m.get("modules")).stream()
                .map(x -> map(x).get("artifact_id")).toList());
        assertEquals(List.of("root", "a", "b"), map(m.get("selection")).get("modules"));
        Map<String, Object> affected = map(m.get("affected"));
        assertEquals(List.of("a", "root"), affected.get("changed_modules"));
        assertEquals("mvn -pl a,. -am -amd verify", affected.get("command"));
        assertTrue(((String) m.get("table")).contains("| b           | jar       | b    | root, a    |"), (String) m.get("table"));
        assertTrue(error(tools, "maven_reactor", "project_dir", tmp.resolve("a/src").toString()).startsWith("No pom.xml"));
        assertTrue(error(tools, "maven_reactor", "project_dir", dir.toString(), "projects", List.of("zz"))
                .startsWith("No module 'zz'"));
    }

    @Test
    void logText() {
        Map<String, Object> m = ok(tools, "maven_log", "text", """
                [INFO] Reactor Summary for root 1:
                [INFO]
                [INFO] root ............................................... SUCCESS [  0.001 s]
                [INFO] core ............................................... FAILURE [  2.5 s]
                [INFO] BUILD FAILURE
                [WARNING] The requested profile "x" could not be activated because it does not exist.
                [ERROR] Failed to execute goal on project core: Could not resolve dependencies for project x:core:jar:1
                [ERROR]   mvn <args> -rf :core
                """, "table_format", "markdown");
        assertEquals("failure", m.get("status"));
        assertEquals("-rf :core", m.get("resume_with"));
        assertEquals("Could not resolve dependencies for project x:core:jar:1",
                map(((List<?>) m.get("goal_failures")).get(0)).get("message"));
        assertEquals("| module | status | seconds |\n| --- | --- | ---: |\n| root | success | 0.001 |\n| core | failure | 2.5 |\n",
                m.get("table"));
        assertTrue(error(tools, "maven_log").contains("Give 'file' or 'text'"));
    }
}
