package io.github.elguardian.mcpsettools.devtools.timeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.elguardian.mcpsettools.devtools.logs.LogFormat;
import io.github.elguardian.mcpsettools.devtools.maven.MavenLogParser;

class TimelineTest {

    static final long T0 = 1_714_557_600_000_000L; // 2024-05-01T10:00:00Z

    static Span span(String thread, long from, long to, String label, String category) {
        return new Span("app", thread, T0 + from * 1000, T0 + to * 1000, label, category, 0, Map.of());
    }

    static Timeline sample() {
        return new Timeline(List.of(
                span("main", 0, 1000, "Server.handle", "method"),
                span("main", 100, 400, "Dao.save", "method"),
                span("worker-1", 200, 700, "Dao.save", "method"),
                span("worker-1", 700, 800, "wait Account@1f", "lock-wait"),
                span("worker-1", 800, 950, "hold Account@1f", "lock-hold")),
                List.of(new Mark("app", "worker-1", T0 + 960_000, "throws java.lang.IllegalStateException", "exception")),
                List.of());
    }

    @Test
    void analysis() {
        Analysis a = new Analysis(sample(), 10);
        assertEquals(2, a.maxConcurrency());
        assertEquals(T0 + 200_000, a.maxConcurrencyAt());
        assertEquals(1, a.overlaps().size());
        Analysis.Overlap o = a.overlaps().get(0);
        assertEquals("Dao.save", o.label());
        assertEquals(200_000, o.duration());
        assertEquals(List.of(1_000_000L, 750_000L), a.lanes().stream().map(Analysis.Lane::busy).toList());
        Analysis.LockStats l = a.locks().get(0);
        assertEquals("Account@1f", l.lock());
        assertEquals(100_000, l.totalWait());
        assertEquals(150_000, l.totalHold());
    }

    @Test
    void ascii() {
        String s = Renderers.render(sample(), Renderers.Format.ASCII, 40, 3, ZoneOffset.UTC);
        assertTrue(s.startsWith("Timeline 2024-05-01 10:00:00.000 -> 2024-05-01 10:00:01.000 (1s), 2 thread(s), 5 span(s)\n"), s);
        String lanes = String.join("\n",
                "         0                  +500ms            +1s",
                "         |------------------|-------------------|",
                "main     AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                "  .1         BBBBBBBBBBBB",
                "worker-1         BBBBBBBBBBBBBBBBBBBB~~~~======",
                "  marks                                        !");
        assertTrue(s.contains(lanes + "\n"), s);
        assertTrue(s.contains("| B | Dao.save        |     2 | 800ms | 500ms |"), s);
        assertTrue(s.endsWith("~ lock wait  = lock held  ! exception\n"), s);
    }

    @Test
    void textFormats() {
        String m = Renderers.render(sample(), Renderers.Format.MERMAID, 0, 3, ZoneOffset.UTC);
        assertTrue(m.contains("    section worker-1\n    Dao.save :t2, 1714557600200, 1714557600700\n"
                + "    wait Account@1f :crit, t3,"), m);
        assertTrue(m.contains("throws java.lang.IllegalStateException :milestone, t5, 1714557600960, 1714557600961"), m);
        String p = Renderers.render(sample(), Renderers.Format.PLANTUML, 0, 3, ZoneOffset.UTC);
        assertTrue(p.startsWith("@startuml\nconcise \"main\" as L0\nconcise \"main .1\" as L1\nconcise \"worker-1\" as L2\n"
                + "\n@0\nL0 is \"Server.handle\"\nL1 is {-}\nL2 is {-}\n@100\nL1 is \"Dao.save\"\n"), p);
        assertTrue(p.contains("@700\nL2 is \"wait Account@1f\"\n"), p);
        assertTrue(p.contains("highlight 960 to 965 #Gold : throws java.lang.IllegalStateException\n@enduml\n"), p);
        String c = Renderers.render(sample(), Renderers.Format.CHROME, 0, 3, ZoneOffset.UTC).replaceAll("\\s+", "");
        assertTrue(c.contains("{\"name\":\"Dao.save\",\"cat\":\"method\",\"ph\":\"X\",\"ts\":100000,\"dur\":300000,"
                + "\"pid\":1,\"tid\":1}"), c);
        assertTrue(c.contains("\"ph\":\"i\",\"s\":\"t\",\"ts\":960000"), c);
        String h = Renderers.render(sample(), Renderers.Format.HTML, 0, 3, ZoneOffset.UTC);
        assertTrue(h.startsWith("<!doctype html>") && h.contains("<svg") && h.contains("<h2>Locks</h2>")
                && h.contains("<h2>Same block on several threads at once</h2>"));
    }

    @Test
    void spansFromLogs(@TempDir Path tmp) throws Exception {
        Path log = Files.writeString(tmp.resolve("app.log"), String.join("\n",
                "10:00:00.000 [http-1] INFO  com.acme.Api - begin request r1",
                "10:00:00.100 [http-2] INFO  com.acme.Api - begin request r2",
                "10:00:00.200 [http-1] INFO  com.acme.Db - query start",
                "10:00:00.500 [http-1] INFO  com.acme.Db - query end",
                "10:00:00.600 [worker] INFO  com.acme.Api - end request r1",
                "10:00:00.900 [http-2] ERROR com.acme.Api - boom",
                "") + "\n");
        LogFormat f = LogFormat.of("logback", null, "UTC");
        Timeline t = LogSource.read(List.of(log), f, List.of(
                new LogSource.Rule("request", Pattern.compile("begin request (?<id>\\w+)"),
                        Pattern.compile("end request (?<id>\\w+)"), "id"),
                new LogSource.Rule("query", Pattern.compile("query start"), Pattern.compile("query end"), null)),
                List.of(new LogSource.Marker("error", Pattern.compile("boom"))));
        assertEquals(List.of("request r1@http-1", "request r2@http-2", "query@http-1"),
                t.spans().stream().map(s -> s.label() + "@" + s.thread()).toList());
        Span r1 = t.spans().get(0);
        assertEquals(600_000, r1.duration());
        assertEquals("worker", r1.args().get("ended_on"));
        assertEquals("no end record before the end of the log", t.spans().get(1).args().get("open"));
        assertEquals(1, t.marks().size());
        assertTrue(t.notes().get(0).startsWith("1 span(s) in app.log have no end record"));
    }

    @Test
    void mavenModules() throws Exception {
        Path log = Path.of(TimelineTest.class.getResource("/maven/fail-T.log").toURI());
        Timeline t = MavenSource.read(MavenLogParser.parse(log));
        assertEquals(List.of("root@mvn-builder-root", "a@mvn-builder-a", "b@mvn-builder-b"),
                t.spans().stream().map(s -> s.label() + "@" + s.thread()).toList());
        assertEquals("failure", t.spans().get(2).category());
        assertTrue(t.notes().get(0).startsWith("1 module(s) have no timestamped lines"));
    }

    @Test
    void filterAndLimit() {
        Timeline t = sample().filter(T0 + 300_000, T0 + 600_000, x -> x.startsWith("worker"), x -> true);
        assertEquals(1, t.spans().size());
        assertEquals(300_000, t.spans().get(0).duration());
        Timeline top = sample().limit(2);
        assertEquals(List.of("Server.handle", "Dao.save"), top.spans().stream().map(Span::label).toList());
        assertTrue(top.notes().get(0).startsWith("Showing the 2 longest of 5 spans"));
    }

    @Test
    void durations() {
        assertEquals("850µs", Renderers.duration(850));
        assertEquals("12.5ms", Renderers.duration(12_500));
        assertEquals("1.23s", Renderers.duration(1_234_000));
        assertEquals("2m05s", Renderers.duration(125_000_000));
    }
}
