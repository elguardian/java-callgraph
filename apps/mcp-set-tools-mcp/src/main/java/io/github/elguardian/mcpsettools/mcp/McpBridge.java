package io.github.elguardian.mcpsettools.mcp;

import java.io.IOException;
import java.util.List;

import io.github.elguardian.mcpsettools.tool.Notifier;
import io.github.elguardian.mcpsettools.tool.Tool;
import io.github.elguardian.mcpsettools.tool.ToolRegistry;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Registry tools as MCP tools: one dispatch path, text results as they are, errors as
 * {@code isError}. A tool's {@link Notifier} becomes {@code notifications/progress} (when the
 * request carries a {@code progressToken}) and {@code notifications/message} (logger: the tool name).
 */
final class McpBridge {

    static final String PREAMBLE = "mcp-set-tools is a toolbox for working on Java projects.";

    private final ToolRegistry registry;
    private final McpJsonMapper json;

    McpBridge(ToolRegistry registry, McpJsonMapper json) {
        this.registry = registry;
        this.json = json;
    }

    /** The preamble, then each provider's paragraph. */
    String instructions() {
        StringBuilder b = new StringBuilder(PREAMBLE);
        registry.instructions().forEach(p -> b.append('\n').append(p));
        return b.toString();
    }

    List<SyncToolSpecification> specifications() {
        return registry.tools().stream().map(this::specification).toList();
    }

    private SyncToolSpecification specification(Tool t) {
        McpSchema.Tool tool = McpSchema.Tool.builder(t.name(), t.inputSchema()).title(t.title()).description(t.description())
                .annotations(McpSchema.ToolAnnotations.builder().title(t.title()).readOnlyHint(t.readOnly())
                        .destructiveHint(false).openWorldHint(false).build())
                .build();
        return SyncToolSpecification.builder().tool(tool)
                .callHandler((exchange, request) -> {
                    CallNotifier notifier = new CallNotifier(exchange,
                            request.meta() == null ? null : request.meta().get("progressToken"), t.name());
                    try {
                        return result(registry.call(t.name(), request.arguments(), notifier));
                    } finally {
                        notifier.callReturned();
                    }
                })
                .build();
    }

    McpSchema.CallToolResult result(ToolRegistry.Result r) {
        if (r.isError()) {
            return text(r.error(), true);
        }
        try {
            // rendered tables and diagrams go out as they are, not as a quoted JSON string
            return text(r.value() instanceof String s ? s : json.writeValueAsString(r.value()), false);
        } catch (IOException e) {
            return text("Cannot serialize the result: " + e, true);
        }
    }

    private static McpSchema.CallToolResult text(String text, boolean error) {
        return McpSchema.CallToolResult.builder().addTextContent(text).isError(error).build();
    }

    /** One call's notifications; progress ends with the call, log messages may follow from its jobs. */
    static final class CallNotifier implements Notifier {

        private final McpSyncServerExchange exchange;
        private final Object progressToken;
        private final String logger;
        private boolean open = true;
        private double last = Double.NEGATIVE_INFINITY;

        CallNotifier(McpSyncServerExchange exchange, Object progressToken, String logger) {
            this.exchange = exchange;
            this.progressToken = progressToken;
            this.logger = logger;
        }

        @Override
        public synchronized void progress(double done, double total, String message) {
            // MCP requires increasing progress values, and none after the response
            if (progressToken == null || !open || done <= last) {
                return;
            }
            last = done;
            try {
                exchange.progressNotification(new McpSchema.ProgressNotification(progressToken, done,
                        total > 0 ? total : null, message));
            } catch (RuntimeException e) {
                // the client went away: the call itself decides what happens next
            }
        }

        @Override
        public void log(Level level, String message) {
            try {
                exchange.loggingNotification(McpSchema.LoggingMessageNotification.builder().level(level(level))
                        .logger(logger).data(message).build());
            } catch (RuntimeException e) {
                // as above
            }
        }

        synchronized void callReturned() {
            open = false;
        }

        static McpSchema.LoggingLevel level(Level level) {
            return switch (level) {
                case DEBUG -> McpSchema.LoggingLevel.DEBUG;
                case INFO -> McpSchema.LoggingLevel.INFO;
                case NOTICE -> McpSchema.LoggingLevel.NOTICE;
                case WARNING -> McpSchema.LoggingLevel.WARNING;
                case ERROR -> McpSchema.LoggingLevel.ERROR;
            };
        }
    }
}
