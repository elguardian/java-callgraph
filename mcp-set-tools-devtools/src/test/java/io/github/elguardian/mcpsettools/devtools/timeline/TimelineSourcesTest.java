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
import io.github.elguardian.mcpsettools.timeline.Span;
import io.github.elguardian.mcpsettools.timeline.Timeline;
import io.github.elguardian.mcpsettools.timeline.Mark;
import io.github.elguardian.mcpsettools.devtools.maven.MavenLogParser;

class TimelineSourcesTest {

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
        Path log = Path.of(TimelineSourcesTest.class.getResource("/maven/fail-T.log").toURI());
        Timeline t = MavenSource.read(MavenLogParser.parse(log));
        assertEquals(List.of("root@mvn-builder-root", "a@mvn-builder-a", "b@mvn-builder-b"),
                t.spans().stream().map(s -> s.label() + "@" + s.thread()).toList());
        assertEquals("failure", t.spans().get(2).category());
        assertTrue(t.notes().get(0).startsWith("1 module(s) have no timestamped lines"));
    }


}
