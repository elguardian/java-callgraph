package io.github.elguardian.mcpsettools.mcp;

import io.github.elguardian.mcpsettools.tool.ToolRegistry;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.elguardian.mcpsettools.testkit.TestCompiler;

/** The fixture program and helpers shared by the tests. */
final class Fixture {

    /** A service with a guarded and an unguarded path to Store.write, and a resource leak. */
    static final String APP = """
            package f;
            public class Main {
                public static void main(String[] args) throws Exception {
                    Service s = new Service();
                    s.handle("read");
                    s.handle("admin");
                }
            }
            class Service {
                void handle(String op) {
                    switch (op) {
                        case "admin" -> admin();
                        case "debug" -> Store.write();
                        default -> Store.read();
                    }
                }
                void admin() { authorize(); Store.write(); }
                void authorize() { }
            }
            class Store {
                static java.io.InputStream open(java.io.File f) throws java.io.IOException {
                    java.io.InputStream in = new java.io.FileInputStream(f);
                    if (f.length() == 0) { return null; }
                    return in;
                }
                static void write() { }
                static void read() { }
                static void read(int n) { }
            }
            """;

    private Fixture() {
    }

    static Path appJar(Path dir) throws Exception {
        return TestCompiler.create().source("f.Main", APP).compile().writeJar(dir.resolve("app.jar"));
    }

    static Map<String, Object> args(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            m.put((String) keyValues[i], keyValues[i + 1]);
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> ok(ServerTools tools, String tool, Object... keyValues) {
        ToolRegistry.Result r = tools.call(tool, args(keyValues));
        assertFalse(r.isError(), () -> tool + ": " + r.error());
        return (Map<String, Object>) r.value();
    }

    /** A tool whose result is plain text (tables, diagrams). */
    static String text(ServerTools tools, String tool, Object... keyValues) {
        ToolRegistry.Result r = tools.call(tool, args(keyValues));
        assertFalse(r.isError(), () -> tool + ": " + r.error());
        return (String) r.value();
    }

    static String error(ServerTools tools, String tool, Object... keyValues) {
        ToolRegistry.Result r = tools.call(tool, args(keyValues));
        assertTrue(r.isError(), () -> tool + " should fail, returned " + r.value());
        return r.error();
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> items(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("items");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }
}
