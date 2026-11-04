package gr.gousiosg.javacg.mcp;

import static gr.gousiosg.javacg.mcp.Fixture.map;
import static gr.gousiosg.javacg.mcp.Fixture.ok;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** bisect_run with the maven oracle on a project whose test starts failing at the fourth commit. */
class GitToolsIT {

    static final String POM = """
            <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
            <groupId>x</groupId><artifactId>calc</artifactId><version>1</version>
            <properties><maven.compiler.release>17</maven.compiler.release>
            <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>
            <dependencyManagement><dependencies><dependency><groupId>org.junit</groupId><artifactId>junit-bom</artifactId>
            <version>6.1.3</version><type>pom</type><scope>import</scope></dependency></dependencies></dependencyManagement>
            <dependencies><dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><scope>test</scope>
            </dependency></dependencies>
            <build><plugins>
            <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.16.0</version></plugin>
            <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><version>3.6.1</version></plugin>
            </plugins></build></project>
            """;

    static final String TEST = """
            package c;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            import org.junit.jupiter.api.Test;
            class CalcTest {
                @Test void four() { assertEquals(4, Calc.four()); }
            }
            """;

    @Test
    void mavenOracleFindsTheBreakingCommit(@TempDir Path tmp) throws Exception {
        TestRepo repo = new TestRepo(tmp.resolve("calc"));
        repo.write("pom.xml", POM).write("src/test/java/c/CalcTest.java", TEST);
        List<String> commits = new ArrayList<>();
        String[] bodies = {
            "return 2 + 2;",
            "return 2 * 2;",
            "return 4; // simplified",
            "return 5; // off by one",
            "return 5 +; // does not compile",
            "return 5;" };
        for (String body : bodies) {
            repo.write("src/main/java/c/Calc.java", "package c;\npublic class Calc {\n    public static int four() {\n        " + body
                    + "\n    }\n}\n");
            commits.add(repo.commit(body));
        }
        JavacgTools tools = new JavacgTools();
        Map<String, Object> started = ok(tools, "bisect_run", "project_dir", repo.dir.toString(), "good", commits.get(0),
                "args", List.of("test"), "offline", true, "fails_test", "CalcTest#four", "step_timeout_seconds", 300,
                "table_format", "ascii");
        String job = (String) started.get("job_id");
        Map<String, Object> status = Map.of();
        for (int i = 0; i < 10; i++) {
            status = ok(tools, "job_status", "job_id", job, "wait_seconds", 60);
            if (!"running".equals(status.get("status"))) {
                break;
            }
        }
        assertEquals("done", status.get("status"), status.toString());
        Map<String, Object> result = map(status.get("result"));
        org.junit.jupiter.api.Assertions.assertNotNull(result.get("first_bad"), result.toString());
        Map<String, Object> first = map(result.get("first_bad"));
        assertEquals(commits.get(3), first.get("commit"));
        assertEquals("return 5; // off by one", first.get("subject"));
        List<?> steps = (List<?>) result.get("steps");
        assertTrue(steps.stream().allMatch(s -> List.of("good", "bad", "skip").contains(map(s).get("verdict"))), steps.toString());
        assertTrue(((String) result.get("table")).contains("| verdict |"));
        // the checkout is still at the last commit
        assertEquals(commits.get(5), repo.git("rev-parse", "HEAD").strip());
        assertEquals(1, repo.git("worktree", "list").lines().count());
    }
}
