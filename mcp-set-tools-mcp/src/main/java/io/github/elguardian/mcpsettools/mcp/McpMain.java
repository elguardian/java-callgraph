package io.github.elguardian.mcpsettools.mcp;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;

import io.github.elguardian.mcpsettools.tool.ToolContext;
import io.github.elguardian.mcpsettools.tool.ToolRegistry;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * {@code java -jar mcp-set-tools-mcp.jar}: an MCP server on stdin/stdout for every tool module on the
 * class path and in the plugins directory. Tool state (models, jobs) lives for the process.
 */
public final class McpMain {

    private McpMain() {
    }

    public static void main(String[] args) throws InterruptedException {
        // stdout carries the protocol: anything else printing there (library code, agents) would corrupt it
        OutputStream protocolOut = new FileOutputStream(FileDescriptor.out);
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.err), true));
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel",
                System.getProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn"));
        System.setProperty("org.slf4j.simpleLogger.logFile", "System.err");

        ToolRegistry registry = registry();
        registry.warnings().forEach(w -> System.err.println("[mcp-set-tools] " + w));
        McpJsonMapper json = McpJsonDefaults.getMapper();
        McpBridge bridge = new McpBridge(registry, json);
        CountDownLatch closed = new CountDownLatch(1);
        // the client ends the session by closing stdin
        StdioServerTransportProvider transport = new StdioServerTransportProvider(json,
                new EofSignallingInput(new FileInputStream(FileDescriptor.in), closed::countDown), protocolOut);
        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("mcp-set-tools", version())
                .instructions(bridge.instructions())
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .jsonMapper(json)
                .tools(bridge.specifications())
                .build();
        Runtime.getRuntime().addShutdownHook(new Thread(server::closeGracefully, "mcp-set-tools-mcp-shutdown"));
        closed.await();
        System.exit(0);
    }

    /** The bundled providers and the plugins', minus the tools disabled in {@code tools.json}. */
    static ToolRegistry registry() {
        List<String> warnings = new ArrayList<>();
        ToolContext base = ToolContext.standard(McpMain.class.getClassLoader());
        Path plugins = PluginLoader.directory(base);
        ClassLoader loader = PluginLoader.load(plugins, McpMain.class.getClassLoader(), warnings);
        ToolContext context = ToolContext.standard(loader);
        ToolsConfig config = ToolsConfig.read(context.home().resolve("tools.json"), warnings);
        warnings.forEach(w -> context.log().println("[mcp-set-tools] " + w));
        return ToolRegistry.load(loader, context, config.disabled(), List.of());
    }

    static String version() {
        try (InputStream in = McpMain.class.getResourceAsStream("version.properties")) {
            Properties p = new Properties();
            if (in != null) {
                p.load(in);
            }
            return p.getProperty("version", "unknown");
        } catch (IOException e) {
            return "unknown";
        }
    }

    /** Runs a callback once when the stream reports end of input. */
    static final class EofSignallingInput extends FilterInputStream {

        private final Runnable onEof;
        private boolean signalled;

        EofSignallingInput(InputStream in, Runnable onEof) {
            super(in);
            this.onEof = onEof;
        }

        @Override
        public int read() throws IOException {
            return check(super.read());
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return check(super.read(b, off, len));
        }

        private int check(int n) {
            if (n < 0 && !signalled) {
                signalled = true;
                onEof.run();
            }
            return n;
        }
    }
}
