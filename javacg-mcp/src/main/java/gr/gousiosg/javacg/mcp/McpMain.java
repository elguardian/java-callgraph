package gr.gousiosg.javacg.mcp;

import gr.gousiosg.javacg.tool.Tool;
import gr.gousiosg.javacg.tool.ToolRegistry;
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
            javacg is a toolbox for working on Java projects.
            Call graphs: javacg combines a static call graph built from bytecode with runtime recordings \
            made by the javacg agent. load_model with the application jars/WAR as app (third-party jars \
            as lib) and/or recording directories as trace; find_methods to get exact method names; then \
            call_paths, check_guard, callers/callees, describe_method, gap_report, hotspots or \
            run_detectors on the returned model_id. To record a running JVM: list_jvms, attach_agent \
            with incl= patterns, recorder start/stop, then load_model with the stopped recording's \
            directory as trace. Method names in results can be passed back as patterns.
            Builds and tests: maven_reactor (modules, build order, what a change affects), maven_run \
            (runs Maven, returns compiler errors, failed tests and module status instead of the raw \
            log), maven_log (parses an existing log); junit_summary, junit_failures and junit_compare \
            read surefire/failsafe/Gradle XML reports.
            Logs: log_query searches, counts, groups and histograms log files by their layout pattern \
            (presets for WildFly, logback, log4j2, Spring Boot, Maven, or 'auto'); log_pattern checks a \
            pattern first. stacktrace_parse explains a pasted stack trace.
            Concurrency: timeline draws what threads did over time from a recording, logs, a maven_run \
            log or given events, with overlap and lock-wait analysis.
            Sustaining: thread_dump_analyze (deadlocks, lock owners, stuck and hot threads), gc_log_analyze \
            and heap_histogram (pauses, leaks), jfr_analyze (CPU, locks, allocation, I/O from a recording or \
            a live pid), watch_method (calls, latency and values of one method in a running JVM), \
            stacktrace_map (which version produced a trace, with source lines), known_issue_match (known \
            issues and Jira), bisect_run (the commit that broke a test). Long tools return a job_id: poll \
            job_status.
            Daily work: daily_status lists open Jira issues assigned to the user and their open GitHub pull \
            requests and GitLab merge requests (authored, review requested, assigned, mentioned), ordered by \
            what needs them first, with new feedback since the last run.
            Output: tools returning lists take table_format (ascii, markdown, jira, slack, csv, tsv, \
            html); table_render formats any rows for pasting into Jira, Slack or a terminal.""";

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

    private static SyncToolSpecification specification(Tool t, JavacgTools tools, McpJsonMapper json) {
        McpSchema.Tool tool = McpSchema.Tool.builder(t.name(), t.inputSchema()).title(t.title()).description(t.description())
                .annotations(McpSchema.ToolAnnotations.builder().title(t.title()).readOnlyHint(t.readOnly())
                        .destructiveHint(false).openWorldHint(false).build())
                .build();
        return SyncToolSpecification.builder().tool(tool).callHandler((exchange, request) -> {
            ToolRegistry.Result r = tools.call(t.name(), request.arguments());
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
