package gr.gousiosg.javacg.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;

/** The packaged server, driven over stdio by the SDK's own client. */
class McpServerIT {

    @Test
    void servesToolsOverStdio(@TempDir Path tmp) throws Exception {
        Path jar = Path.of(System.getProperty("javacg.mcp.jar"));
        assertTrue(Files.isRegularFile(jar), "server jar not built: " + jar);
        Path app = Fixture.appJar(tmp);
        McpJsonMapper json = McpJsonDefaults.getMapper();
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        StdioClientTransport transport = new StdioClientTransport(
                ServerParameters.builder(java).args("-jar", jar.toString()).build(), json);
        McpSyncClient client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(60)).build();
        try {
            McpSchema.InitializeResult init = client.initialize();
            assertEquals("javacg", init.serverInfo().name());
            assertTrue(init.instructions().contains("load_model"));

            List<String> names = client.listTools().tools().stream().map(McpSchema.Tool::name).toList();
            assertTrue(names.containsAll(List.of("load_model", "call_paths", "check_guard", "run_detectors",
                    "attach_agent", "recorder", "table_render", "stacktrace_parse", "junit_summary", "junit_failures",
                    "junit_compare", "log_query", "log_pattern", "maven_reactor", "maven_run", "maven_log", "timeline")),
                    names.toString());
            assertEquals(28, names.size(), names.toString());

            // plain-text results (tables, diagrams) arrive unquoted
            McpSchema.CallToolResult table = client.callTool(McpSchema.CallToolRequest.builder("table_render")
                    .arguments(Map.of("format", "jira", "columns", List.of("a"), "rows", List.of(List.of("x")))).build());
            assertEquals("||a||\n|x|\n", text(table));

            Map<String, Object> loaded = call(client, json, "load_model", Map.of("app", List.of(app.toString())));
            assertEquals("m1", loaded.get("model_id"));

            Map<String, Object> guard = call(client, json, "check_guard", Map.of("model_id", "m1",
                    "from", "f.Main.main", "to", "f.Store.write", "through", "f.Service.admin"));
            assertEquals(false, guard.get("ok"));

            McpSchema.CallToolResult bad = client.callTool(McpSchema.CallToolRequest.builder("find_methods")
                    .arguments(Map.of("model_id", "m9", "pattern", "*")).build());
            assertTrue(bad.isError());
            assertTrue(text(bad).startsWith("Unknown model_id m9"), text(bad));
        } finally {
            client.closeGracefully();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> call(McpSyncClient client, McpJsonMapper json, String tool,
            Map<String, Object> args) throws Exception {
        McpSchema.CallToolResult r = client.callTool(McpSchema.CallToolRequest.builder(tool).arguments(args).build());
        assertFalse(Boolean.TRUE.equals(r.isError()), () -> tool + ": " + text(r));
        return json.readValue(text(r), Map.class);
    }

    private static String text(McpSchema.CallToolResult r) {
        return ((McpSchema.TextContent) r.content().get(0)).text();
    }
}
