package io.github.elguardian.mcpsettools.devtools.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import io.github.elguardian.mcpsettools.devtools.junit.TestCase.Status;

class ReportReaderTest {

    static Path resource(String name) throws Exception {
        return Path.of(ReportReaderTest.class.getResource("/junit/" + name).toURI());
    }

    @Test
    void readsStatusesProblemsAndOutput() throws Exception {
        TestRun run = new TestRun(new ReportReader().read(List.of(resource("run1"))));
        assertEquals(new TestRun.Totals(5, 1, 1, 1, 1, 1, 3.5), run.totals());
        TestCase total = run.cases().get(0);
        assertEquals("shop", total.module());
        assertEquals(Status.FAILED, total.status());
        assertEquals("expected: <10> but was: <12>", total.mainProblem().message());
        assertEquals("computing total for order 17\n", total.systemOut());
        TestCase retry = run.cases().get(3);
        assertEquals(Status.FLAKY, retry.status());
        assertEquals(1, retry.reruns());
        assertTrue(retry.mainProblem().stackTrace().startsWith("java.util.concurrent.TimeoutException: timeout"));
        assertEquals(Status.SKIPPED, run.cases().get(2).status());
        assertNull(run.cases().get(2).mainProblem().stackTrace());
        assertEquals("com.acme.OrderTest#save", run.slowest(1).get(0).id());
    }

    @Test
    void groupsFailuresBySignature() throws Exception {
        TestRun run = new TestRun(new ReportReader().read(List.of(resource("run1"))));
        List<TestRun.FailureGroup> groups = run.failureGroups(List.of("com.acme"));
        assertEquals(2, groups.size());
        assertEquals(java.util.Set.of("org.opentest4j.AssertionFailedError at com.acme.OrderTest.total",
                "java.lang.IllegalStateException <- java.lang.NullPointerException at com.acme.Keys.next"
                        + " < com.acme.OrderService.save < com.acme.OrderTest.save"),
                groups.stream().map(TestRun.FailureGroup::signature).collect(java.util.stream.Collectors.toSet()));
        assertEquals("expected: <*> but was: <*>", TestRun.template("expected: <10> but was: <12>"));
    }

    @Test
    void comparesRuns() throws Exception {
        ReportReader reader = new ReportReader();
        TestRun before = new TestRun(reader.read(List.of(resource("run1"))));
        TestRun after = new TestRun(reader.read(List.of(resource("run2"))));
        TestRun.Comparison c = after.compareTo(before, 50, 0.1);
        assertEquals(List.of("com.acme.OrderTest#fast"), c.newlyFailing().stream().map(TestCase::id).toList());
        assertEquals(List.of("com.acme.OrderTest#total"), c.fixed().stream().map(TestCase::id).toList());
        assertEquals(List.of("com.acme.OrderTest#save"), c.stillFailing().stream().map(TestCase::id).toList());
        assertEquals(List.of("com.acme.OrderTest#brandNew"), c.added().stream().map(TestCase::id).toList());
        assertEquals(List.of("shop:com.acme.OrderTest#later", "shop:com.acme.OrderTest#retry"), c.removed());
        assertEquals("com.acme.OrderTest#fast", c.slower().get(0).test().id());
        // same signature in both runs although the line moved
        assertEquals(TestRun.signature(before.cases().get(1), List.of()), TestRun.signature(after.cases().get(1), List.of()));
    }

    @Test
    void cachesByModificationTime() throws Exception {
        ReportReader reader = new ReportReader();
        Path f = resource("run1/shop/target/surefire-reports/TEST-com.acme.OrderTest.xml");
        assertSame(reader.readFile(f), reader.readFile(f));
    }

    @Test
    void readsThisProjectsOwnReports() throws Exception {
        Path analyze = Path.of("../mcp-set-tools-analyze/target/surefire-reports");
        Assumptions.assumeTrue(Files.isDirectory(analyze), "mcp-set-tools-analyze not built");
        TestRun run = new TestRun(new ReportReader().read(List.of(Path.of(".."))));
        assertTrue(run.byModule().containsKey("mcp-set-tools-analyze"), run.byModule().keySet().toString());
        assertTrue(run.totals().tests() > 20);
    }
}
