package gr.gousiosg.javacg.mcp;

import java.util.Map;

/**
 * One MCP tool, independent of the SDK so the tools can be called directly from tests.
 *
 * @param readOnly    true when the tool only reads files and loaded models
 * @param inputSchema JSON schema of the arguments, see {@link Schema}
 * @param handler     returns a JSON-serializable value (maps, lists, strings, numbers, booleans)
 */
record ToolDef(String name, String title, String description, boolean readOnly, Map<String, Object> inputSchema,
        Handler handler) {

    @FunctionalInterface
    interface Handler {
        Object call(ToolArgs args) throws Exception;
    }
}
