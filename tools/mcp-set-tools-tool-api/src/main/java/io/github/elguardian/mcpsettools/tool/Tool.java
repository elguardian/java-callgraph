package io.github.elguardian.mcpsettools.tool;

import java.util.Map;

/**
 * One tool: what a host (the MCP server, a test) lists and calls. Independent of any protocol.
 *
 * @param readOnly    true when the tool only reads files and loaded models
 * @param inputSchema JSON schema of the arguments, see {@link Schema}
 * @param handler     returns a JSON-serializable value (maps, lists, strings, numbers, booleans)
 */
public record Tool(String name, String title, String description, boolean readOnly, Map<String, Object> inputSchema,
        Handler handler) {

    @FunctionalInterface
    public interface Handler {
        Object call(ToolArgs args) throws Exception;
    }
}
