package io.github.elguardian.mcpsettools.mcp;

import java.io.IOException;
import java.util.List;

import io.github.elguardian.mcpsettools.tool.Tool;
import io.github.elguardian.mcpsettools.tool.ToolRegistry;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;

/** Registry tools as MCP tools: one dispatch path, text results as they are, errors as {@code isError}. */
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
                .callHandler((exchange, request) -> result(registry.call(t.name(), request.arguments())))
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
}
