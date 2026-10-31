package gr.gousiosg.javacg.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import gr.gousiosg.javacg.cli.StaticCommandTest.Run;
import gr.gousiosg.javacg.testkit.ForkedJvm;
import gr.gousiosg.javacg.testkit.TestCompiler;

/** Static analysis + agent run + CLI on the same fixture program. */
class EndToEndIT {

    static Path appJar;
    static Path out;

    @BeforeAll
    static void record(@TempDir Path tmp) throws Exception {
        Path agent = Path.of(System.getProperty("javacg.agent.jar"));
        assertTrue(Files.isRegularFile(agent), "agent jar not built: " + agent);
        appJar = TestCompiler.create().source("e.Main", """
                package e;
                public class Main {
                    public static void main(String[] args) throws Exception {
                        Service s = new Service();
                        s.handle("read");
                        s.handle("admin");
                        Main.class.getDeclaredMethod("viaReflection").invoke(null);
                    }
                    static void viaReflection() { }
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
                    static void neverCalled() { }
                }
                """).compile().writeJar(tmp.resolve("app.jar"));
        out = tmp.resolve("out");
        ForkedJvm.create().classpath(appJar).jvmArg("-javaagent:" + agent + "=incl=e\\..*;jmx=off;out=" + out)
                .mainClass("e.Main").run().assertSuccess();
    }

    @Test
    void mergeReportsGaps() {
        Run r = StaticCommandTest.run("merge", "--app", appJar.toString(), "--trace", out.toString(), "--limit", "-1");
        assertEquals(0, r.code(), r.err());
        assertTrue(r.out().contains("e.Main.main(java.lang.String[]) -> e.Main.viaReflection()  x1"), r.out());
        // the debug branch never ran
        assertTrue(r.out().contains("Static edges never exercised although the caller ran: 1\n"
                + "  e.Service.handle(java.lang.String) -> e.Store.write()  (line 15)"), r.out());
        assertTrue(r.out().contains("  e.Store.neverCalled()"), r.out());
    }

    @Test
    void guardQueryFindsTheUnguardedBranch() {
        Run bad = StaticCommandTest.run("query", "guard", "--app", appJar.toString(), "--trace", out.toString(),
                "--from", "e.Main.main", "--to", "e.Store.write", "--through", "e.Service.admin");
        assertEquals(QueryCommand.VIOLATION, bad.code(), bad.out() + bad.err());
        assertTrue(bad.out().contains("-> e.Store.write()   [static, line 15]"), bad.out());

        // only the calls that actually ran: the write went through admin()
        Run runtime = StaticCommandTest.run("query", "guard", "--trace", out.toString(), "--edges", "dynamic",
                "--from", "e.Main.main", "--to", "e.Store.write", "--through", "e.Service.admin");
        assertEquals(0, runtime.code(), runtime.out() + runtime.err());
    }

    @Test
    void analyzeReportsFindingsAndFailsTheBuildOnRequest() {
        Run r = StaticCommandTest.run("analyze", "--app", appJar.toString(), "--trace", out.toString());
        assertEquals(0, r.code(), r.err());
        assertTrue(r.out().contains("resource.leak"), r.out());
        assertTrue(r.out().contains("e.Store.open(java.io.File)"), r.out());
        Run failing = StaticCommandTest.run("analyze", "--app", appJar.toString(), "--detector", "resources",
                "--fail-on", "medium");
        assertEquals(AnalyzeCommand.FINDINGS, failing.code(), failing.out());
        Path sarif = out.resolveSibling("report.sarif");
        Run withSarif = StaticCommandTest.run("analyze", "--app", appJar.toString(), "--trace", out.toString(),
                "--sarif", sarif.toString(), "--source-root", "src/main/java");
        assertEquals(0, withSarif.code(), withSarif.err());
        try {
            String text = Files.readString(sarif);
            assertTrue(text.contains("\"uri\": \"src/main/java/e/Main.java\""), text);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        Run bogus = StaticCommandTest.run("analyze", "--app", appJar.toString(), "--detector", "nope");
        assertEquals(1, bogus.code());
    }

    @Test
    void pathsAndCallers() {
        Run paths = StaticCommandTest.run("query", "paths", "--app", appJar.toString(), "--from", "e.Main.main",
                "--to", "e.Store.read");
        assertEquals(0, paths.code(), paths.err());
        assertTrue(paths.out().contains("path 1 (2 calls):"), paths.out());

        Run callers = StaticCommandTest.run("query", "callers", "--trace", out.toString(), "--method", "e.Store.*");
        assertTrue(callers.out().contains("  1  e.Service.handle(java.lang.String)"), callers.out());
        assertTrue(callers.out().contains("  2  e.Main.main(java.lang.String[])"), callers.out());

        Run none = StaticCommandTest.run("query", "callers", "--trace", out.toString(), "--method", "nope.X.y");
        assertEquals(1, none.code());
        assertTrue(none.err().contains("matches no method"), none.err());
    }
}
