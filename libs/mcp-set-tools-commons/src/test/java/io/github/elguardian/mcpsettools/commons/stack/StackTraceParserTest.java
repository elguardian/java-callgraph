package io.github.elguardian.mcpsettools.commons.stack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class StackTraceParserTest {

    static final String JUNIT = """
            org.opentest4j.AssertionFailedError: expected: <1> but was: <2>
            \tat org.junit.jupiter.api.AssertionFailureBuilder.build(AssertionFailureBuilder.java:151)
            \tat org.junit.jupiter.api.Assertions.assertEquals(Assertions.java:527)
            \tat com.acme.OrderTest.total(OrderTest.java:42)
            \tat java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:103)
            \tat java.base/java.lang.reflect.Method.invoke(Method.java:580)
            \tat org.junit.platform.commons.util.ReflectionUtils.invokeMethod(ReflectionUtils.java:767)
            """;

    static final String NESTED = """
            java.lang.IllegalStateException: cannot save order 17
            line two of the message
            \tat app//com.acme.OrderService.save(OrderService.java:88)
            \tat app//com.acme.Api.post(Api.java:12)
            \tSuppressed: java.io.IOException: close failed
            \t\tat com.acme.Db.close(Db.java:5)
            \t\t... 2 more
            Caused by: java.sql.SQLException: duplicate key
            \tat org.h2.Driver.execute(Driver.java:1)
            \tat com.acme.Dao.insert(Dao.java:30)
            \t... 2 more
            Caused by: java.lang.NullPointerException
            \tat com.acme.Keys.next(Keys.java:9)
            \t... 4 more
            """;

    @Test
    void parsesFramesAndModules() {
        StackTrace t = StackTraceParser.parse(JUNIT).orElseThrow();
        assertEquals("org.opentest4j.AssertionFailedError", t.exception());
        assertEquals("expected: <1> but was: <2>", t.message());
        assertEquals(6, t.frames().size());
        StackTrace.Frame reflect = t.frames().get(3);
        assertEquals("java.base", reflect.module());
        assertEquals("jdk.internal.reflect.DirectMethodHandleAccessor", reflect.className());
        assertEquals(103, reflect.line());
    }

    @Test
    void causesSuppressedAndOmitted() {
        StackTrace t = StackTraceParser.parse(NESTED).orElseThrow();
        assertEquals("cannot save order 17\nline two of the message", t.message());
        assertNull(t.frames().get(0).module());
        assertEquals(1, t.suppressed().size());
        assertEquals(2, t.suppressed().get(0).omitted());
        assertEquals(List.of("java.lang.IllegalStateException", "java.sql.SQLException", "java.lang.NullPointerException"),
                t.chain().stream().map(StackTrace::exception).toList());
        assertNull(t.root().message());
        assertEquals(4, t.root().omitted());
    }

    @Test
    void findsTracesInsideLogs() {
        String log = """
                10:00:01 INFO  starting com.acme.Main
                10:00:02 ERROR request failed
                java.lang.RuntimeException: boom
                \tat com.acme.Api.get(Api.java:3)
                10:00:03 INFO  com.acme.NotAnException: just text
                java.lang.IllegalArgumentException: no frames, not a trace
                10:00:04 INFO  done
                """;
        List<StackTrace> all = StackTraceParser.parseAll(log);
        assertEquals(1, all.size());
        assertEquals("boom", all.get(0).message());
    }

    @Test
    void trimAndSignature() {
        StackTrace t = StackTraceParser.parse(JUNIT).orElseThrow();
        String trimmed = StackTraces.render(t, true, List.of("com.acme"));
        assertEquals("""
                org.opentest4j.AssertionFailedError: expected: <1> but was: <2>
                \tat org.junit.jupiter.api.AssertionFailureBuilder.build(AssertionFailureBuilder.java:151)
                \t... 1 frame (org.junit)
                \tat com.acme.OrderTest.total(OrderTest.java:42)
                \t... 3 frames (jdk.internal, java.lang, org.junit)
                """, trimmed);
        assertEquals("org.opentest4j.AssertionFailedError at com.acme.OrderTest.total",
                StackTraces.signature(t, List.of(), 3));
        StackTrace n = StackTraceParser.parse(NESTED).orElseThrow();
        assertEquals("java.lang.IllegalStateException <- java.lang.NullPointerException at com.acme.Keys.next"
                + " < com.acme.Dao.insert < com.acme.OrderService.save", StackTraces.signature(n, List.of("com.acme"), 3));
        assertEquals("java.lang.NullPointerException (com.acme.Keys.next:9)", StackTraces.oneLine(n, List.of("com.acme")));
        assertTrue(StackTraces.render(n, false, List.of()).contains("\tSuppressed: java.io.IOException: close failed\n"
                + "\t\tat com.acme.Db.close(Db.java:5)\n\t\t... 2 more\n"));
    }
}
