package io.github.elguardian.mcpsettools.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ToolRegistryTest {

    private static Tool tool(String name, Object value) {
        return new Tool(name, name, "returns " + value, true, Schema.object().build(), args -> value);
    }

    private static ToolProvider provider(String id, String instructions, Tool... tools) {
        return new ToolProvider() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public List<Tool> tools(ToolContext context) {
                return List.of(tools);
            }

            @Override
            public String instructions() {
                return instructions;
            }
        };
    }

    private static ToolRegistry registry(Set<String> disabled, ToolProvider... providers) {
        return ToolRegistry.of(ToolContext.standard(), List.of(providers), disabled);
    }

    private static List<String> names(ToolRegistry r) {
        return r.tools().stream().map(Tool::name).toList();
    }

    @Test
    void firstProviderWinsADuplicateName() {
        ToolRegistry r = registry(Set.of(), provider("a", null, tool("x", 1), tool("y", 2)),
                provider("b", null, tool("x", 3), tool("z", 4)));
        assertEquals(List.of("x", "y", "z"), names(r));
        assertEquals(1, r.call("x", Map.of()).value());
        assertEquals("a", r.provider("x"));
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("tool x of provider b ignored")), r.warnings().toString());
    }

    @Test
    void aFailingProviderIsSkipped() {
        ToolProvider broken = new ToolProvider() {
            @Override
            public String id() {
                return "broken";
            }

            @Override
            public List<Tool> tools(ToolContext context) {
                throw new IllegalStateException("no config");
            }
        };
        ToolRegistry r = registry(Set.of(), broken, provider("ok", "Use ok.", tool("ok", "fine")));
        assertEquals(List.of("ok"), names(r));
        assertEquals("fine", r.call("ok", Map.of()).value());
        assertEquals(List.of("Use ok."), r.instructions());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("broken") && w.contains("no config")), r.warnings().toString());
    }

    @Test
    void disabledToolsAndProvidersAreLeftOut() {
        ToolRegistry r = registry(Set.of("y", "provider:b"), provider("a", "Use a.", tool("x", 1), tool("y", 2)),
                provider("b", "Use b.", tool("z", 3)));
        assertEquals(List.of("x"), names(r));
        assertEquals(List.of("Use a."), r.instructions());
        assertTrue(r.call("z", Map.of()).isError());
        assertTrue(r.call("y", Map.of()).error().contains("y"), r.call("y", Map.of()).error());
    }

    @Test
    void handlerErrorsBecomeResults() {
        Tool bad = new Tool("bad", "bad", "fails", true, Schema.object().build(), args -> {
            throw new ToolException("bad input");
        });
        ToolRegistry r = registry(Set.of(), provider("a", null, bad));
        ToolRegistry.Result res = r.call("bad", Map.of());
        assertTrue(res.isError());
        assertEquals("bad input", res.error());
    }
}
