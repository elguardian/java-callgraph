package io.github.elguardian.mcpsettools.mcp;

import io.github.elguardian.mcpsettools.testkit.ToolHarness;
import io.github.elguardian.mcpsettools.tool.Tool;
import static io.github.elguardian.mcpsettools.testkit.ToolHarness.error;
import static io.github.elguardian.mcpsettools.testkit.ToolHarness.items;
import static io.github.elguardian.mcpsettools.testkit.ToolHarness.map;
import static io.github.elguardian.mcpsettools.testkit.ToolHarness.ok;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnalysisToolsTest {

    @TempDir
    Path tmp;

    ToolHarness tools;
    Path app;

    @BeforeEach
    void load() throws Exception {
        tools = new ToolHarness();
        app = Fixture.appJar(tmp);
        Map<String, Object> m = ok(tools, "load_model", "app", List.of(app.toString()));
        assertEquals("m1", m.get("model_id"));
        assertEquals(3, map(m.get("static")).get("app_classes"));
    }

    @Test
    void reloadingTheSameInputsKeepsTheId() {
        assertEquals("m1", ok(tools, "load_model", "app", app.toString()).get("model_id"));
        assertEquals("m2", ok(tools, "load_model", "app", List.of(app.toString()), "algorithm", "rta")
                .get("model_id"));
        assertTrue(error(tools, "find_methods", "pattern", "*").contains("pass model_id"));
        assertEquals(2, ((List<?>) ok(tools, "list_models").get("models")).size());
        ok(tools, "unload_model", "model_id", "m2");
        assertTrue(error(tools, "find_methods", "model_id", "m2", "pattern", "*").startsWith("Unknown model_id m2"));
    }

    @Test
    void findMethodsRetriesWithoutPackage() {
        Map<String, Object> m = ok(tools, "find_methods", "pattern", "Store.read");
        assertEquals(2, m.get("total"));
        assertEquals("f.Store.read()", items(m).get(0).get("method"));
        assertEquals(true, items(m).get(0).get("app_method"));
    }

    @Test
    void describeMethodNeedsOneMatch() {
        assertTrue(error(tools, "describe_method", "method", "f.Store.read").contains("matches 2 methods"));
        Map<String, Object> m = ok(tools, "describe_method", "method", "f.Store.read(int)");
        assertEquals("f.Store.read(int)", m.get("method"));
        Map<String, Object> d = ok(tools, "describe_method", "method", "f.Service.admin()");
        List<Map<String, Object>> callees = items(map(d.get("callees")));
        assertEquals(List.of("f.Service.authorize()", "f.Store.write()"),
                callees.stream().map(c -> c.get("callee")).toList());
        assertEquals("static", callees.get(0).get("provenance"));
    }

    @Test
    void guardFindsTheUnguardedBranch() {
        Map<String, Object> bad = ok(tools, "check_guard", "from", "f.Main.main", "to", "f.Store.write",
                "through", "f.Service.admin");
        assertEquals(false, bad.get("ok"));
        List<?> steps = (List<?>) map(bad.get("counterexample")).get("steps");
        assertEquals("f.Service.handle(java.lang.String)", map(steps.get(steps.size() - 2)).get("method"));
        assertEquals(true, ok(tools, "check_guard", "from", "f.Service.admin", "to", "f.Store.write",
                "through", "f.Service.admin").get("ok"));
    }

    @Test
    void pathsAndReachability() {
        Map<String, Object> p = ok(tools, "call_paths", "from", "f.Main.main", "to", "f.Store.write");
        assertEquals(1, p.get("found"));
        Map<String, Object> path = map(((List<?>) p.get("paths")).get(0));
        assertEquals(2, path.get("calls"));
        assertEquals(List.of(13), map(((List<?>) path.get("steps")).get(2)).get("lines"));
        Map<String, Object> callers = ok(tools, "callers", "method", "f.Store.write");
        assertTrue(items(callers).stream().anyMatch(r -> r.get("method").equals("f.Main.main(java.lang.String[])")
                && r.get("distance").equals(2)), callers.toString());
        assertTrue(error(tools, "callees", "method", "f.Nope.x").contains("matches no method"));
    }

    @Test
    void detectorsAndSarif() throws Exception {
        Path sarif = tmp.resolve("out/report.sarif");
        Map<String, Object> m = ok(tools, "run_detectors", "detectors", List.of("resources"), "sarif_file",
                sarif.toString());
        assertEquals("resource.leak", items(m).get(0).get("rule"));
        assertEquals("f.Store.open(java.io.File)", items(m).get(0).get("method"));
        assertTrue(Files.readString(sarif).contains("\"resource.leak\""));
        assertTrue(error(tools, "run_detectors", "detectors", List.of("bogus")).contains("Unknown detector"));
        assertTrue(error(tools, "run_detectors", "min_severity", "huge").contains("must be one of"));
        List<?> detectors = (List<?>) ok(tools, "list_detectors").get("detectors");
        assertTrue(detectors.stream().anyMatch(d -> map(d).get("id").equals("locks")));
    }

    @Test
    void runtimeToolsNeedRecordings() {
        assertTrue(error(tools, "gap_report").contains("needs recordings"));
        assertTrue(error(tools, "hotspots").contains("need recordings"));
    }

    @Test
    void exportSubgraph() throws Exception {
        Path dot = tmp.resolve("g.dot");
        Map<String, Object> m = ok(tools, "export_graph", "output", dot.toString(), "format", "dot",
                "from", "f.Service.admin");
        assertEquals(3, m.get("methods"));
        assertEquals(2, m.get("edges"));
        assertTrue(Files.readString(dot).startsWith("digraph"));
    }

    @Test
    void loadErrors() {
        ToolHarness fresh = new ToolHarness();
        assertTrue(error(fresh, "load_model").contains("Give 'app' inputs"));
        assertTrue(error(fresh, "load_model", "app", tmp.resolve("missing.jar").toString())
                .startsWith("Input does not exist"));
        assertTrue(error(fresh, "find_methods", "pattern", "*").contains("call load_model first"));
    }

    @Test
    void everyToolHasAnObjectSchema() {
        for (Tool t : tools.all()) {
            assertEquals("object", t.inputSchema().get("type"), t.name());
            assertTrue(t.description().length() > 20, t.name());
        }
    }
}
