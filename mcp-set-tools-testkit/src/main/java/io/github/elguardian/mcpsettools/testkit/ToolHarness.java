package io.github.elguardian.mcpsettools.testkit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.elguardian.mcpsettools.tool.Tool;
import io.github.elguardian.mcpsettools.tool.ToolContext;
import io.github.elguardian.mcpsettools.tool.ToolRegistry;

/** The tools of the providers on the test class path, called as a host would, with assertion helpers. */
public final class ToolHarness {

    private final ToolRegistry registry;

    public ToolHarness() {
        this(ToolContext.standard());
    }

    public ToolHarness(ToolContext context) {
        registry = ToolRegistry.load(ToolHarness.class.getClassLoader(), context, Set.of(), List.of());
    }

    public ToolRegistry registry() {
        return registry;
    }

    public List<Tool> all() {
        return registry.tools();
    }

    public ToolRegistry.Result call(String name, Map<String, Object> arguments) {
        return registry.call(name, arguments);
    }

    public static Map<String, Object> args(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            m.put((String) keyValues[i], keyValues[i + 1]);
        }
        return m;
    }

    /** A successful call returning a JSON object. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> ok(ToolHarness tools, String tool, Object... keyValues) {
        ToolRegistry.Result r = tools.call(tool, args(keyValues));
        assertFalse(r.isError(), () -> tool + ": " + r.error());
        return (Map<String, Object>) r.value();
    }

    /** A successful call returning plain text (tables, diagrams). */
    public static String text(ToolHarness tools, String tool, Object... keyValues) {
        ToolRegistry.Result r = tools.call(tool, args(keyValues));
        assertFalse(r.isError(), () -> tool + ": " + r.error());
        return (String) r.value();
    }

    /** A failing call: its error message. */
    public static String error(ToolHarness tools, String tool, Object... keyValues) {
        ToolRegistry.Result r = tools.call(tool, args(keyValues));
        assertTrue(r.isError(), () -> tool + " should fail, returned " + r.value());
        return r.error();
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> items(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("items");
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }
}
