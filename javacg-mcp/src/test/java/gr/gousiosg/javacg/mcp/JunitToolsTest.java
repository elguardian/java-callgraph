package gr.gousiosg.javacg.mcp;

import static gr.gousiosg.javacg.mcp.Fixture.error;
import static gr.gousiosg.javacg.mcp.Fixture.items;
import static gr.gousiosg.javacg.mcp.Fixture.map;
import static gr.gousiosg.javacg.mcp.Fixture.ok;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JunitToolsTest {

    @TempDir
    Path tmp;

    JavacgTools tools = new JavacgTools();
    Path before;
    Path after;

    @BeforeEach
    void reports() throws Exception {
        before = write("before", """
                <testsuite name="a.T" time="1.0">
                  <testcase classname="a.T" name="ok" time="0.1"/>
                  <testcase classname="a.T" name="bad" time="0.9">
                    <failure type="java.lang.AssertionError" message="boom 42"><![CDATA[java.lang.AssertionError: boom 42
                	at org.junit.Assert.fail(Assert.java:1)
                	at a.T.bad(T.java:7)
                ]]></failure>
                    <system-out>line1
                line2
                </system-out>
                  </testcase>
                </testsuite>
                """);
        after = write("after", """
                <testsuite name="a.T" time="3.0">
                  <testcase classname="a.T" name="ok" time="2.5"/>
                  <testcase classname="a.T" name="bad" time="0.5"/>
                </testsuite>
                """);
    }

    private Path write(String run, String xml) throws Exception {
        Path dir = tmp.resolve(run).resolve("mod/target/surefire-reports");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("TEST-a.T.xml"), xml);
        return tmp.resolve(run);
    }

    @Test
    void summary() {
        Map<String, Object> m = ok(tools, "junit_summary", "project_dir", before.toString(), "table_format", "markdown");
        assertEquals(1, map(m.get("totals")).get("failed"));
        assertEquals("mod", map(((List<?>) m.get("by_module")).get(0)).get("module"));
        Map<String, Object> f = map(((List<?>) m.get("failures")).get(0));
        assertEquals("a.T.bad:7", f.get("where"));
        assertEquals("boom 42", f.get("message"));
        assertTrue(((String) m.get("table")).startsWith("| module | tests |"), (String) m.get("table"));
    }

    @Test
    void failuresWithTrimmedStackAndOutput() {
        Map<String, Object> m = ok(tools, "junit_failures", "paths", List.of(before.toString()), "include_output", true,
                "output_lines", 1, "table_format", "jira");
        Map<String, Object> f = items(m).get(0);
        assertEquals("java.lang.AssertionError: boom 42\n\tat org.junit.Assert.fail(Assert.java:1)\n"
                + "\tat a.T.bad(T.java:7)\n", f.get("stack_trace"));
        assertEquals("line2", f.get("stdout_tail"));
        assertEquals("||test||status||exception||message||where||\n|a.T#bad|failed|java.lang.AssertionError|boom 42|a.T.bad:7|\n",
                m.get("table"));
        assertEquals(0, ok(tools, "junit_failures", "paths", List.of(before.toString()), "test", "*#ok").get("total"));
    }

    @Test
    void compare() {
        Map<String, Object> m = ok(tools, "junit_compare", "baseline", List.of(before.toString()), "current",
                List.of(after.toString()), "table_format", "ascii");
        assertEquals(List.of("a.T#bad"), map(m.get("fixed")).get("items"));
        assertEquals("a.T#ok", items(map(m.get("slower"))).get(0).get("test"));
        assertTrue(((String) m.get("table")).contains("| a.T#ok  | slower: 0.1 s -> 2.5 s |"), (String) m.get("table"));
    }

    @Test
    void noReports() throws Exception {
        Path empty = Files.createDirectories(tmp.resolve("empty"));
        assertTrue(error(tools, "junit_summary", "project_dir", empty.toString()).startsWith("No JUnit XML reports"));
        assertTrue(error(tools, "junit_summary", "paths", List.of(tmp.resolve("missing").toString()))
                .startsWith("Does not exist"));
    }
}
