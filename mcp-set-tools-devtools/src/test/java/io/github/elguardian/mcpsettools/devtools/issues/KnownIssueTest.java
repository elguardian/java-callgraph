package io.github.elguardian.mcpsettools.devtools.issues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.elguardian.mcpsettools.commons.stack.StackTraceParser;

class KnownIssueTest {

    static final List<String> APP = List.of("org.acme");

    static final String KNOWN = """
            java.lang.IllegalStateException: cannot save order 17
            \tat org.acme.OrderService.save(OrderService.java:88)
            \tat org.acme.Api.post(Api.java:12)
            Caused by: java.sql.SQLException: duplicate key "k1"
            \tat org.acme.Dao.insert(Dao.java:30)
            \t... 2 more
            """;

    /** The same failure on another line and another order id. */
    static final String SAME = KNOWN.replace("OrderService.java:88", "OrderService.java:91").replace("17", "42");

    /** The same root cause reached from another entry point. */
    static final String RELATED = """
            java.lang.RuntimeException: batch failed
            \tat org.acme.Batch.run(Batch.java:5)
            Caused by: java.sql.SQLException: duplicate key "k9"
            \tat org.acme.Dao.insert(Dao.java:30)
            \tat org.acme.Batch.run(Batch.java:4)
            """;

    static KnownIssue.Fingerprint fp(String text) {
        return KnownIssue.Fingerprint.of(StackTraceParser.parse(text).orElseThrow(), APP);
    }

    @Test
    void storeRoundTripAndMatching(@TempDir Path tmp) throws Exception {
        IssueStore store = new IssueStore(tmp.resolve("issues.jsonl"));
        KnownIssue known = KnownIssue.of("ACME-1", "Duplicate key on save", "Resolved", List.of("1.4.2"), List.of("1.4.0"),
                "https://jira.example.com/browse/ACME-1", fp(KNOWN), "retry \"quoted\"\nsecond line", "manual");
        assertEquals(1, store.add(List.of(known)));
        assertEquals(0, store.add(List.of(known)), "same key and signature replaces");
        KnownIssue loaded = store.load().get(0);
        assertEquals(known, loaded);
        assertEquals(List.of("org.acme.Dao.insert", "org.acme.OrderService.save", "org.acme.Api.post"), loaded.frames());

        IssueMatcher.Candidate same = IssueMatcher.match(fp(SAME), store.load(), 0.3, 5).get(0);
        assertEquals(1.0, same.score());
        assertEquals(List.of("same signature"), same.reasons());
        List<IssueMatcher.Candidate> related = IssueMatcher.match(fp(RELATED), store.load(), 0.3, 5);
        assertEquals(1, related.size());
        assertTrue(related.get(0).score() < 1.0 && related.get(0).score() > 0.5, related.toString());
        assertTrue(related.get(0).reasons().contains("same root exception SQLException"), related.toString());
        assertTrue(IssueMatcher.match(fp("java.lang.NullPointerException\n\tat org.acme.X.y(X.java:1)\n"), store.load(), 0.3, 5)
                .isEmpty());
    }

    @Test
    void jiraCsv() throws Exception {
        String csv = "Summary,Issue key,Issue id,Status,Fix Version/s,Fix Version/s,Description,Comment,Comment\n"
                + "\"Duplicate key on save\",ACME-7,1007,Closed,1.4.2,1.5.0,\"Customer sees:\n{code}\n" + KNOWN.replace("\"", "\"\"")
                + "{code}\",\"01/May/24 10:00 AM;jdoe;Also seen in batch:\n" + RELATED.replace("\"", "\"\"") + "\",\n"
                + "\"No trace here\",ACME-8,1008,Open,,,\"just words\",,\n";
        JiraCsvImport.Result r = JiraCsvImport.read(csv, "https://jira.example.com/", APP);
        assertEquals(2, r.rows());
        assertEquals(1, r.rowsWithoutTrace());
        assertEquals(2, r.issues().size());
        KnownIssue first = r.issues().get(0);
        assertEquals("ACME-7", first.key());
        assertEquals(List.of("1.4.2", "1.5.0"), first.fixedIn());
        assertEquals("https://jira.example.com/browse/ACME-7", first.url());
        assertEquals("java.sql.SQLException", r.issues().get(1).rootException());
        assertEquals("jira-csv", first.source());
        Path f = Files.createTempFile("x", ".jsonl");
        assertEquals(2, new IssueStore(f).add(r.issues()));
    }
}
