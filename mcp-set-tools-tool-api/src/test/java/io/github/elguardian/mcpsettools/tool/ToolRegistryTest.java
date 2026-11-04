package io.github.elguardian.mcpsettools.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

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

    private static Notifier recording(List<String> got) {
        return new Notifier() {
            @Override
            public void progress(double done, double total, String message) {
                got.add("progress " + done + "/" + total + " " + message);
            }

            @Override
            public void log(Level level, String message) {
                got.add(level + " " + message);
            }
        };
    }

    @Test
    void theCallsNotifierReachesTheHandler() {
        List<String> got = new ArrayList<>();
        Tool t = new Tool("p", "p", "notifies", true, Schema.object().build(), args -> {
            args.progress(1, 2, "half");
            args.notifier().log(Notifier.Level.WARNING, "careful");
            return "x";
        });
        ToolRegistry r = registry(Set.of(), provider("a", null, t));
        assertEquals("x", r.call("p", Map.of(), recording(got)).value());
        assertEquals(List.of("progress 1.0/2.0 half", "WARNING careful"), got);
        assertEquals("x", r.call("p", Map.of()).value());
    }

    @Test
    void jobsLogTheirProgressAndTheirEnd() throws Exception {
        Jobs jobs = new Jobs();
        List<String> got = Collections.synchronizedList(new ArrayList<>());
        Jobs.Job ok = jobs.start("work", recording(got), job -> {
            job.progress("step 1");
            return "r";
        });
        Jobs.Job bad = jobs.start("work", recording(got), job -> {
            throw new ToolException("broke");
        });
        assertTrue(ok.await(10, TimeUnit.SECONDS));
        assertTrue(bad.await(10, TimeUnit.SECONDS));
        assertTrue(got.contains("INFO job " + ok.id + " (work): step 1"), got.toString());
        assertTrue(got.stream().anyMatch(l -> l.startsWith("NOTICE job " + ok.id + " (work) done after")), got.toString());
        assertTrue(got.stream().anyMatch(l -> l.startsWith("ERROR job " + bad.id + " (work) failed") && l.endsWith("broke")),
                got.toString());
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
