package io.github.elguardian.mcpsettools.mcp;

import io.github.elguardian.mcpsettools.tool.ToolArgs;
import io.github.elguardian.mcpsettools.tool.Results;
import static io.github.elguardian.mcpsettools.mcp.Fixture.error;
import static io.github.elguardian.mcpsettools.mcp.Fixture.ok;
import static io.github.elguardian.mcpsettools.mcp.Fixture.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class TableToolsTest {

    ServerTools tools = new ServerTools();

    @Test
    void rendersArrayAndObjectRowsAndCsv() {
        assertEquals("||module||time||\n|core|1.2|\n", text(tools, "table_render", "format", "jira",
                "columns", List.of("module", "time"), "rows", List.of(List.of("core", 1.2))));
        assertEquals("| a | b |\n| --- | ---: |\n| x | 2 |\n", text(tools, "table_render", "format", "markdown",
                "rows", List.of(Map.of("a", "x", "b", 2)), "columns", List.of("a", "b")));
        assertEquals("a\tb\n1\t2\n", text(tools, "table_render", "format", "tsv", "csv", "a;b\n1;2\n",
                "separator", "semicolon"));
        assertTrue(error(tools, "table_render", "rows", List.of(List.of("x"))).contains("need 'columns'"));
        assertTrue(error(tools, "table_render", "columns", List.of("a"), "rows", List.of(List.of("x")),
                "align", List.of("sideways")).contains("left, right or center"));
    }

    @Test
    void parsesStackTraces() {
        Map<String, Object> m = ok(tools, "stacktrace_parse", "app_packages", List.of("com.acme"), "text", """
                java.lang.IllegalStateException: save failed
                \tat com.acme.Service.save(Service.java:10)
                Caused by: java.lang.NullPointerException: key
                \tat com.acme.Keys.next(Keys.java:4)
                \t... 1 more
                """);
        assertEquals("java.lang.IllegalStateException", m.get("exception"));
        assertEquals("java.lang.NullPointerException", Fixture.map(m.get("root_cause")).get("exception"));
        assertEquals("com.acme.Keys.next(Keys.java:4)", m.get("first_app_frame"));
        assertEquals(8, ((String) m.get("signature_id")).length());
        assertTrue(error(tools, "stacktrace_parse", "text", "nothing here").contains("No stack trace"));
    }

    @Test
    void tableFormatOptionRendersLists() {
        Map<String, Object> into = new java.util.LinkedHashMap<>();
        Results.table(into, List.of(Map.of("m", "a", "lines", List.of(1, 2))), List.of("m", "lines"),
                new ToolArgs(Map.of("table_format", "ascii")));
        assertEquals("+---+-------+\n| m | lines |\n+---+-------+\n| a | 1, 2  |\n+---+-------+\n", into.get("table"));
    }
}
