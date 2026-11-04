package gr.gousiosg.javacg.mcp;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.concurrent.CountDownLatch;

import gr.gousiosg.javacg.analyze.report.SarifWriter;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * {@code java -jar javacg-mcp.jar}: an MCP server on stdin/stdout. Models stay loaded for the
 * life of the process, so the static analysis of a deployment is done once per session.
 */
public final class McpMain {

    static final String INSTRUCTIONS = """
            javacg analyzes Java programs by combining a static call graph built from bytecode with \
            runtime recordings made by the javacg agent. Typical workflow: load_model with the \
            application jars/WAR as app (third-party jars as lib) and/or recording directories as trace; \
            find_methods to get exact method names; then call_paths, check_guard, callers/callees, \
            describe_method, gap_report, hotspots or run_detectors on the returned model_id. To record a \
            running JVM: list_jvms, attach_agent with incl= patterns, recorder start/stop, then \
            load_model with the stopped recording's directory as trace. Method names in results can be \
            passed back as patterns.""";

    private McpMain() {
    }

    public static void main(String[] args) throws InterruptedException {
        // stdout carries the protocol: anything else printing there (library code, agents) would corrupt it
        OutputStream protocolOut = new FileOutputStream(FileDescriptor.out);
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.err), true));
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel",
                System.getProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn"));
        System.setProperty("org.slf4j.simpleLogger.logFile", "System.err");

        McpJsonMapper json = McpJsonDefaults.getMapper();
        JavacgTools tools = new JavacgTools();
        CountDownLatch closed = new CountDownLatch(1);
        // the client ends the session by closing stdin
        StdioServerTransportProvider transport = new StdioServerTransportProvider(json,
                new EofSignallingInput(new FileInputStream(FileDescriptor.in), closed::countDown), protocolOut);
        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("javacg", SarifWriter.version())
                .instructions(INSTRUCTIONS)
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .jsonMapper(json)
                .tools(tools.all().stream().map(t -> specification(t, tools, json)).toList())
                .build();
        Runtime.getRuntime().addShutdownHook(new Thread(server::closeGracefully, "javacg-mcp-shutdown"));
        closed.await();
        System.exit(0);
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

    private static SyncToolSpecification specification(ToolDef t, JavacgTools tools, McpJsonMapper json) {
        McpSchema.Tool tool = McpSchema.Tool.builder(t.name(), t.inputSchema()).title(t.title()).description(t.description())
                .annotations(McpSchema.ToolAnnotations.builder().title(t.title()).readOnlyHint(t.readOnly())
                        .destructiveHint(false).openWorldHint(false).build())
                .build();
        return SyncToolSpecification.builder().tool(tool).callHandler((exchange, request) -> {
            JavacgTools.Result r = tools.call(t.name(), request.arguments());
            if (r.isError()) {
                return McpSchema.CallToolResult.builder().addTextContent(r.error()).isError(true).build();
            }
            String text;
            try {
                // rendered tables and diagrams go out as they are, not as a quoted JSON string
                text = r.value() instanceof String s ? s : json.writeValueAsString(r.value());
            } catch (IOException e) {
                return McpSchema.CallToolResult.builder().addTextContent("Cannot serialize the result: " + e)
                        .isError(true).build();
            }
            return McpSchema.CallToolResult.builder().addTextContent(text).isError(false).build();
        }).build();
    }
}
