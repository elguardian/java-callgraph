package io.github.elguardian.mcpsettools.logs;

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

import io.github.elguardian.mcpsettools.timeline.Span;
import io.github.elguardian.mcpsettools.timeline.Timeline;
import io.github.elguardian.mcpsettools.timeline.Mark;

class LogSourceTest {

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
}
