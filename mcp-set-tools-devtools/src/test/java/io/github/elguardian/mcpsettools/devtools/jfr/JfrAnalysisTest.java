package io.github.elguardian.mcpsettools.devtools.jfr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.elguardian.mcpsettools.devtools.timeline.JfrSource;
import io.github.elguardian.mcpsettools.timeline.Timeline;
import io.github.elguardian.mcpsettools.testkit.ForkedJvm;
import io.github.elguardian.mcpsettools.testkit.TestCompiler;

/** A real recording: a CPU-bound method, two threads fighting for a monitor, allocations, exceptions. */
class JfrAnalysisTest {

    static Path jfr;

    @BeforeAll
    static void record(@TempDir Path tmp) throws Exception {
        Path app = TestCompiler.create().source("w.Work", """
                package w;
                public class Work {
                    static final Object LOCK = new Object();
                    static volatile long sink;
                    public static void main(String[] args) throws Exception {
                        Thread a = new Thread(Work::contend, "contender-a");
                        Thread b = new Thread(Work::contend, "contender-b");
                        a.start(); b.start();
                        long end = System.currentTimeMillis() + 2500;
                        while (System.currentTimeMillis() < end) {
                            sink += burn(200_000);
                            sink += new byte[64 * 1024].length;
                            try { Integer.parseInt("x"); } catch (NumberFormatException e) { sink++; }
                        }
                        a.join(); b.join();
                    }
                    static long burn(int n) { long x = 0; for (int i = 0; i < n; i++) { x += i * 31L ^ (x >>> 3); } return x; }
                    static void contend() {
                        for (int i = 0; i < 20; i++) {
                            synchronized (LOCK) {
                                try { Thread.sleep(60); } catch (InterruptedException e) { return; }
                            }
                        }
                    }
                }
                """).compile().writeJar(tmp.resolve("w.jar"));
        jfr = tmp.resolve("rec.jfr");
        ForkedJvm.create().classpath(app).jvmArg("-XX:StartFlightRecording=filename=" + jfr + ",settings=profile")
                .mainClass("w.Work").run().assertSuccess();
        assertTrue(Files.size(jfr) > 0);
    }

    @Test
    void sections() throws Exception {
        JfrAnalysis a = JfrAnalysis.read(jfr, new JfrAnalysis.Options(null, null, null, List.of("w"), 4));
        assertTrue(a.samples() > 10, "samples: " + a.samples());
        assertTrue(a.hotSelf(3).stream().anyMatch(g -> g.key.equals("w.Work.burn")), a.hotSelf(5).stream().map(g -> g.key).toList().toString());
        assertTrue(a.hotApp(1).get(0).key.startsWith("w.Work.burn:"), a.hotApp(1).get(0).key);
        assertEquals("java.lang.String[]", JfrAnalysis.javaName("[Ljava.lang.String;"));
        JfrAnalysis.Group monitor = a.monitors(1).get(0);
        assertTrue(monitor.key.startsWith("monitor java.lang.Object at w.Work.contend"), monitor.key);
        assertTrue(monitor.totalMs > 500, "waited " + monitor.totalMs);
        assertTrue(a.allocByClass(3).stream().anyMatch(g -> g.key.equals("byte[]")), a.allocByClass(5).stream().map(g -> g.key).toList().toString());
        assertTrue(a.throwables() > 100, "throwables " + a.throwables());
        assertTrue(a.sampleThreads(1).get(0).key.equals("main"));
        assertTrue(a.start() != null && a.end().isAfter(a.start()));
    }

    @Test
    void threadFilterAndTimeline() throws Exception {
        JfrAnalysis only = JfrAnalysis.read(jfr, new JfrAnalysis.Options(java.util.regex.Pattern.compile("contender"), null,
                null, List.of("w"), 4));
        assertTrue(only.hotSelf(5).stream().noneMatch(g -> g.key.equals("w.Work.burn")));
        assertTrue(only.monitors(1).get(0).count > 0);
        Timeline t = JfrSource.read(jfr, 10_000);
        assertTrue(t.spans().stream().anyMatch(s -> s.category().equals("lock-wait") && s.thread().startsWith("contender")));
    }
}
