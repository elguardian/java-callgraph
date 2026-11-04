package io.github.elguardian.mcpsettools.mcp;

import static io.github.elguardian.mcpsettools.mcp.Fixture.map;
import static io.github.elguardian.mcpsettools.mcp.Fixture.ok;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.elguardian.mcpsettools.testkit.TestCompiler;

/** Takes thread dumps of a deadlocked JVM by pid. */
class ThreadToolsIT {

    static final String DEADLOCK = """
            package d;
            public class Deadlock {
                static final Object A = new Object(), B = new Object();
                public static void main(String[] args) throws Exception {
                    new Thread(() -> { synchronized (A) { pause(); synchronized (B) { } } }, "t-a").start();
                    new Thread(() -> { synchronized (B) { pause(); synchronized (A) { } } }, "t-b").start();
                    Thread.sleep(300);
                    System.out.println("ready");
                    System.out.flush();
                    Thread.sleep(120_000);
                }
                static void pause() { try { Thread.sleep(100); } catch (InterruptedException e) { } }
            }
            """;

    @Test
    void dumpsALiveJvm(@TempDir Path tmp) throws Exception {
        Path app = TestCompiler.create().source("d.Deadlock", DEADLOCK).compile().writeJar(tmp.resolve("d.jar"));
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process p = new ProcessBuilder(java, "-cp", app.toString(), "d.Deadlock").redirectErrorStream(true).start();
        try {
            BufferedReader out = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            assertEquals("ready", out.readLine());
            Map<String, Object> m = ok(new ServerTools(), "thread_dump_analyze", "pid", Long.toString(p.pid()), "count", 2,
                    "interval_seconds", 1);
            assertEquals(2, m.get("dumps"));
            assertTrue(Files.isRegularFile(Path.of((String) m.get("dump_file"))));
            Map<String, Object> d = map(((List<?>) m.get("deadlocks")).get(0));
            assertEquals(Set.of("t-a", "t-b"), Set.copyOf((List<?>) d.get("threads")));
            assertEquals(true, d.get("reported_by_jvm"));
            assertTrue(((List<?>) m.get("stuck")).stream().anyMatch(s -> map(s).get("thread").equals("t-a")));
        } finally {
            p.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
        }
    }
}
