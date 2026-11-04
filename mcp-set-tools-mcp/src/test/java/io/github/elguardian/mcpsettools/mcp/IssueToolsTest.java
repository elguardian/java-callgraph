package io.github.elguardian.mcpsettools.mcp;

import io.github.elguardian.mcpsettools.testkit.ToolHarness;
import static io.github.elguardian.mcpsettools.testkit.ToolHarness.error;
import static io.github.elguardian.mcpsettools.testkit.ToolHarness.map;
import static io.github.elguardian.mcpsettools.testkit.ToolHarness.ok;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

import io.modelcontextprotocol.json.McpJsonDefaults;

class IssueToolsTest {

    static final String TRACE = """
            java.lang.IllegalStateException: cannot save order 17
            \tat org.acme.OrderService.save(OrderService.java:88)
            Caused by: java.sql.SQLException: duplicate key
            \tat org.acme.Dao.insert(Dao.java:30)
            \t... 1 more
            """;

    @TempDir
    Path tmp;

    ToolHarness tools = new ToolHarness();

    @Test
    void storeAddMatchAndInputs() throws Exception {
        String store = tmp.resolve("issues.jsonl").toString();
        Map<String, Object> added = ok(tools, "known_issue_add", "key", "ACME-1", "title", "Duplicate key", "text", TRACE,
                "fixed_in", List.of("1.4.2"), "store", store, "app_packages", List.of("org.acme"));
        assertEquals("ACME-1", added.get("added"));
        Map<String, Object> m = ok(tools, "known_issue_match", "text", TRACE.replace("17", "99"), "store", store, "jira", false,
                "app_packages", List.of("org.acme"));
        Map<String, Object> c = map(((List<?>) map(((List<?>) m.get("traces")).get(0)).get("candidates")).get(0));
        assertEquals("ACME-1", c.get("key"));
        assertEquals(1.0, c.get("score"));
        assertEquals(List.of("1.4.2"), c.get("fixed_in"));

        Path log = Files.writeString(tmp.resolve("server.log"), "2024-05-01 10:00:00,000 ERROR [org.acme] (default task-1) failed\n"
                + TRACE);
        Map<String, Object> fromLog = ok(tools, "known_issue_match", "log", List.of(log.toString()), "store", store, "jira", false,
                "app_packages", List.of("org.acme"));
        assertTrue(((String) map(((List<?>) fromLog.get("traces")).get(0)).get("from")).startsWith("log: 1 occurrence"));
        assertTrue(error(tools, "known_issue_match", "store", store).startsWith("Give 'text'"));
    }

    @Test
    void jiraCsvImport() throws Exception {
        Path csv = Files.writeString(tmp.resolve("export.csv"), "Issue key,Summary,Status,Fix Version/s,Description\n"
                + "ACME-9,Duplicate key,Closed,2.0,\"" + TRACE + "\"\n");
        String store = tmp.resolve("s.jsonl").toString();
        Map<String, Object> m = ok(tools, "known_issue_import", "csv", csv.toString(), "store", store, "base_url",
                "https://jira.example.com");
        assertEquals(1, m.get("traces_imported"));
        assertTrue(Files.readString(Path.of(store)).contains("\"url\":\"https://jira.example.com/browse/ACME-9\""));
    }

    @Test
    void searchesJira() throws Exception {
        List<String> queries = new ArrayList<>();
        List<String> auth = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rest/api/2/search", ex -> {
            queries.add(URLDecoder.decode(ex.getRequestURI().getRawQuery(), StandardCharsets.UTF_8));
            auth.add(ex.getRequestHeaders().getFirst("Authorization"));
            String body = McpJsonDefaults.getMapper().writeValueAsString(Map.of("issues", List.of(
                    Map.of("key", "WFLY-100", "fields", Map.of("summary", "Duplicate key when saving orders",
                            "status", Map.of("name", "Resolved"), "fixVersions", List.of(Map.of("name", "30.0.1")),
                            "description", "Seen in production:\n{code}\n" + TRACE + "{code}",
                            "comment", Map.of("comments", List.of(Map.of("body", "same here"))))),
                    Map.of("key", "WFLY-200", "fields", Map.of("summary", "Unrelated", "description", "no trace")))));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
        System.setProperty("mcpsettools.jira.url", "http://127.0.0.1:" + server.getAddress().getPort() + "/");
        System.setProperty("mcpsettools.jira.token", "secret-token");
        try {
            Map<String, Object> m = ok(tools, "known_issue_match", "text", TRACE, "store", tmp.resolve("empty.jsonl").toString(),
                    "projects", List.of("WFLY"), "save_jira", true, "app_packages", List.of("org.acme"));
            List<?> candidates = (List<?>) map(((List<?>) m.get("traces")).get(0)).get("candidates");
            Map<String, Object> best = map(candidates.get(0));
            assertEquals("WFLY-100", best.get("key"));
            assertEquals(1.0, best.get("score"));
            assertEquals(List.of("30.0.1"), best.get("fixed_in"));
            assertEquals("jira", best.get("from"));
            assertTrue(best.get("url").toString().endsWith("/browse/WFLY-100"));
            assertEquals("WFLY-200", map(candidates.get(1)).get("key"));
            assertEquals("Bearer secret-token", auth.get(0));
            assertTrue(queries.get(0).contains("text ~ \"\\\"SQLException\\\"\" AND text ~ \"\\\"Dao.insert\\\"\""
                    + " AND project in (WFLY) ORDER BY updated DESC"), queries.get(0));
            assertEquals(1, m.get("saved_to_store"));
        } finally {
            System.clearProperty("mcpsettools.jira.url");
            System.clearProperty("mcpsettools.jira.token");
            server.stop(0);
        }
    }
}
