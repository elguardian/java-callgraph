package gr.gousiosg.javacg.devtools.logs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class LogsTest {

    static Path log(String name) throws Exception {
        return Path.of(LogsTest.class.getResource("/logs/" + name).toURI());
    }

    static List<LogRecord> read(String file, LogFormat f) throws Exception {
        List<LogRecord> all = new ArrayList<>();
        LogReader.read(List.of(log(file)), f, all::add);
        return all;
    }

    @Test
    void wildflyLayoutWithMultiLineTraces() throws Exception {
        List<LogRecord> r = read("server.log", LogFormat.of("%d{yyyy-MM-dd HH:mm:ss,SSS} %-5p [%c] (%t) %s%e%n", null, "UTC"));
        assertEquals(6, r.size());
        LogRecord tx = r.get(1);
        assertEquals("WARN", tx.level());
        assertEquals("ServerService Thread Pool -- 74", tx.thread());
        assertEquals("org.jboss.as.txn", tx.logger());
        assertTrue(tx.message().startsWith("WFLYTX0013"));
        assertEquals(Instant.parse("2024-05-01T10:00:01.250Z"), tx.instant());
        LogRecord err = r.get(2);
        assertEquals(6, err.continuation().size());
        assertEquals("java.sql.SQLException", err.stackTrace().root().exception());
    }

    @Test
    void presetsAndDetection() throws Exception {
        assertEquals("wildfly", LogFormat.detect(LogReader.head(log("server.log"), 20)).orElseThrow().name());
        assertEquals("logback", LogFormat.detect(LogReader.head(log("logback.log"), 20)).orElseThrow().name());
        assertEquals("spring-boot", LogFormat.detect(LogReader.head(log("spring.log"), 20)).orElseThrow().name());
        assertEquals("maven", LogFormat.detect(LogReader.head(log("maven.log"), 20)).orElseThrow().name());
        assertTrue(LogFormat.detect(List.of("just text", "more text")).isEmpty());

        LogRecord boot = read("spring.log", LogFormat.of("spring-boot", null, null)).get(1);
        assertEquals("nio-8080-exec-1", boot.thread());
        assertEquals("o.s.web.servlet.PageNotFound", boot.logger());
        assertEquals(Instant.parse("2024-05-01T08:00:01.456Z"), boot.instant());

        LogRecord mvn = read("maven.log", LogFormat.of("maven", null, "UTC")).get(2);
        assertEquals("WARN", mvn.level());
        assertEquals("mvn-builder-core", mvn.thread());
        assertEquals(Instant.parse("1970-01-01T10:00:03Z"), mvn.instant());
    }

    @Test
    void layoutFeatures() {
        LogFormat f = LogFormat.of("%highlight{%-5level} %d{ISO8601} [%15.15t] %X{req} %logger{1}:%L - %m%n", null, "UTC");
        LogRecord r = f.parseLine("INFO  2024-05-01T10:00:00,001 [         worker] r-77 Svc:42 - hello world", null, 1)
                .orElseThrow();
        assertEquals("worker", r.thread());
        assertEquals("r-77", r.fields().get("mdcreq"));
        assertEquals("42", r.fields().get("line"));
        assertEquals("hello world", r.message());
        LogFormat re = LogFormat.of("regex:^(?<ts>\\S+) (?<level>\\w+) (?<message>.*)$", null, "UTC");
        assertEquals(Instant.parse("2024-05-01T10:00:00Z"),
                re.parseLine("2024-05-01T10:00:00 info x", null, 1).orElseThrow().instant());
        assertEquals("INFO", re.parseLine("2024-05-01T10:00:00 info x", null, 1).orElseThrow().level());
        assertNull(LogFormat.of("%m%n", null, null).parseLine("x", null, 1).orElseThrow().level());
    }

    @Test
    void queries() throws Exception {
        LogFormat f = LogFormat.of("wildfly", null, "UTC");
        List<Path> files = List.of(log("server.log"));
        LogQuery.Options opts = new LogQuery.Options(10, false, 0, LogQuery.GroupBy.TEMPLATE, true, 60_000_000L,
                List.of("com.acme"));
        LogQuery.Result all = LogQuery.run(files, f, LogQuery.Filter.all(), opts);
        assertEquals(6, all.matched());
        assertEquals(2L, all.byLevel().get("ERROR"));
        assertEquals("Order <*> failed", all.groups().get(0).key());
        assertEquals(2, all.groups().get(0).count());
        // both errors have one signature although their lines and messages differ
        assertEquals(1, all.errors().size());
        assertEquals(2, all.errors().get(0).count());
        assertEquals(Set.of("default task-1", "default task-2"), all.errors().get(0).threads());
        assertEquals(List.of(4L, 2L), all.histogram().values().stream()
                .map(m -> m.values().stream().mapToLong(Long::longValue).sum()).toList());

        ZoneId utc = ZoneOffset.UTC;
        LogQuery.Filter warnUp = new LogQuery.Filter("WARN", Set.of(), Pattern.compile("task"), null, null, true, null,
                LogQuery.TimeBound.parse("10:00:02.900", utc), false, utc);
        LogQuery.Result w = LogQuery.run(files, f, warnUp, new LogQuery.Options(10, false, 1, null, false, 0, List.of()));
        assertEquals(1, w.matched());
        assertEquals(List.of(true, false, true), w.hits().stream().map(LogQuery.Hit::context).toList());

        LogQuery.Filter sql = new LogQuery.Filter(null, Set.of(), null, null, Pattern.compile("SQLException"), true, null,
                null, false, utc);
        LogQuery.Result tail = LogQuery.run(files, f, sql, new LogQuery.Options(1, true, 0, null, false, 0, List.of()));
        assertEquals(2, tail.matched());
        assertEquals("Order 18 failed", tail.hits().get(0).record().message());
        assertTrue(tail.truncated());
    }
}
