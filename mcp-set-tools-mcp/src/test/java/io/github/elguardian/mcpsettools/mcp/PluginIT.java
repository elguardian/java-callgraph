package io.github.elguardian.mcpsettools.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.elguardian.mcpsettools.testkit.Compiled;
import io.github.elguardian.mcpsettools.testkit.TestCompiler;
import io.github.elguardian.mcpsettools.tool.ToolProvider;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;

/** A tool jar dropped into the plugins directory, and tools turned off in tools.json. */
class PluginIT {

    static final String PROVIDER = """
            package p;
            import java.util.List;
            import io.github.elguardian.mcpsettools.tool.*;
            public class EchoProvider implements ToolProvider {
                public String id() { return "echo"; }
                public List<Tool> tools(ToolContext context) {
                    return List.of(new Tool("echo", "Echo", "Returns its text, reversed", true,
                            Schema.object().string("text", "Text to reverse", true).build(),
                            args -> {
                                args.progress(1, 2, "half way");
                                args.notifier().log(Notifier.Level.WARNING, "echoing " + args.string("text", ""));
                                return new StringBuilder(args.string("text", "")).reverse().toString();
                            }));
                }
                public String instructions() { return "Echo: echo reverses text."; }
            }
            """;

    @Test
    void loadsPluginsAndHonoursTheDisabledList(@TempDir Path home) throws Exception {
        Path toolApi = Path.of(ToolProvider.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Compiled compiled = TestCompiler.create().classpath(toolApi).source("p.EchoProvider", PROVIDER).compile();
        Path plugins = Files.createDirectories(home.resolve("plugins"));
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(plugins.resolve("echo.jar")))) {
            for (Map.Entry<String, byte[]> c : compiled.classes().entrySet()) {
                jar.putNextEntry(new JarEntry(c.getKey().replace('.', '/') + ".class"));
                jar.write(c.getValue());
            }
            jar.putNextEntry(new JarEntry("META-INF/services/" + ToolProvider.class.getName()));
            jar.write("p.EchoProvider\n".getBytes(StandardCharsets.UTF_8));
        }
        Files.writeString(home.resolve("tools.json"), "{\"disabled\": [\"maven_run\", \"provider:status\"]}");

        Path server = Path.of(System.getProperty("mcpsettools.mcp.jar"));
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        StdioClientTransport transport = new StdioClientTransport(ServerParameters.builder(java)
                .args("-jar", server.toString()).addEnvVar("MCPSETTOOLS_HOME", home.toString()).build(),
                McpJsonDefaults.getMapper());
        List<McpSchema.ProgressNotification> progress = new CopyOnWriteArrayList<>();
        List<McpSchema.LoggingMessageNotification> logs = new CopyOnWriteArrayList<>();
        McpSyncClient client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(60))
                .progressConsumer(progress::add).loggingConsumer(logs::add).build();
        try {
            McpSchema.InitializeResult init = client.initialize();
            assertTrue(init.instructions().contains("Echo: echo reverses text."), init.instructions());
            assertFalse(init.instructions().contains("daily_status"), init.instructions());

            List<String> names = client.listTools().tools().stream().map(McpSchema.Tool::name).toList();
            assertTrue(names.contains("echo"), names.toString());
            assertTrue(names.contains("maven_reactor"), names.toString());
            assertFalse(names.contains("maven_run"), names.toString());
            assertFalse(names.contains("daily_status"), names.toString());

            assertTrue(init.capabilities().logging() != null, "logging capability");
            McpSchema.CallToolResult r = client.callTool(McpSchema.CallToolRequest.builder("echo")
                    .arguments(Map.of("text", "abc")).progressToken("t1").build());
            assertFalse(Boolean.TRUE.equals(r.isError()));
            assertEquals("cba", ((McpSchema.TextContent) r.content().get(0)).text());

            // notifications travel ahead of the response on the same stream; the client hands them over on its own thread
            long deadline = System.nanoTime() + 10_000_000_000L;
            while ((progress.isEmpty() || logs.isEmpty()) && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertEquals(1, progress.size(), progress.toString());
            assertEquals("t1", progress.get(0).progressToken());
            assertEquals(1.0, progress.get(0).progress());
            assertEquals(2.0, progress.get(0).total());
            assertEquals("half way", progress.get(0).message());
            assertEquals(1, logs.size(), logs.toString());
            assertEquals(McpSchema.LoggingLevel.WARNING, logs.get(0).level());
            assertEquals("echo", logs.get(0).logger());
            assertEquals("echoing abc", logs.get(0).data());
        } finally {
            client.closeGracefully();
        }
    }
}
