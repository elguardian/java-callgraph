package gr.gousiosg.javacg.devtools.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import gr.gousiosg.javacg.commons.stack.StackTrace;
import gr.gousiosg.javacg.commons.stack.StackTraceParser;

class SourceMapperTest {

    static final String V1 = """
            package org.acme;

            public class Svc {
                private final Dao dao = new Dao();

                public void save(String id) {
                    Runnable r = () -> {
                        dao.insert(id);
                    };
                    r.run();
                }
            }
            """;

    /** Ten lines inserted above save(): every frame line of v1 moves. */
    static final String V2 = V1.replace("    public void save(String id) {", """
                /**
                 * Saves the order.
                 *
                 * <p>Added documentation moves the method down.
                 *
                 * @param id the order id
                 */
                @Deprecated
                public void old() {
                }
                public void save(String id) {""");

    static final String DAO = """
            package org.acme;

            class Dao {
                void insert(String id) {
                    throw new IllegalStateException("duplicate " + id);
                }
            }
            """;

    /** Frames as v1 produced them. */
    static final String TRACE = """
            java.lang.IllegalStateException: duplicate 7
            \tat org.acme.Dao.insert(Dao.java:5)
            \tat org.acme.Svc.lambda$save$0(Svc.java:8)
            \tat org.acme.Svc.save(Svc.java:10)
            \tat java.base/java.lang.Thread.run(Thread.java:1474)
            """;

    static Path repo;

    @BeforeAll
    static void repo(@TempDir Path tmp) throws Exception {
        repo = tmp.resolve("repo");
        Path src = repo.resolve("core/src/main/java/org/acme");
        Files.createDirectories(src);
        git("init", "-q");
        Files.writeString(src.resolve("Svc.java"), V1);
        Files.writeString(src.resolve("Dao.java"), DAO);
        commit("v1");
        Files.writeString(src.resolve("Svc.java"), V2);
        commit("v2");
    }

    static void commit(String tag) throws Exception {
        git("add", "-A");
        git("-c", "user.name=t", "-c", "user.email=t@example.com", "commit", "-q", "-m", tag);
        git("tag", tag);
    }

    static String git(String... args) throws Exception {
        return new Git(repo).run(args);
    }

    @Test
    void mapsFramesToTheRightVersion() throws Exception {
        StackTrace t = StackTraceParser.parse(TRACE).orElseThrow();
        SourceMapper.RefResult v1 = SourceMapper.map(new Git(repo), "v1", t, 1);
        assertEquals(3, v1.checked());
        assertEquals(3, v1.matching());
        SourceMapper.FrameResult lambda = v1.frames().get(1);
        assertEquals("core/src/main/java/org/acme/Svc.java", lambda.path());
        assertEquals("save", lambda.enclosing());
        assertEquals(">    8              dao.insert(id);", lambda.context().get(1));
        assertEquals(SourceMapper.Status.NOT_FOUND, v1.frames().get(3).status());

        SourceMapper.RefResult v2 = SourceMapper.map(new Git(repo), "v2", t, 0);
        assertEquals(1, v2.matching(), "only Dao did not change");
        assertEquals(SourceMapper.Status.MISMATCH, v2.frames().get(1).status());
        assertEquals(SourceMapper.Status.MISMATCH, v2.frames().get(2).status());
        assertTrue(v2.frames().get(2).note().startsWith("line 10 is not code"), v2.frames().get(2).note());

        List<SourceMapper.RefResult> ranked = SourceMapper.rank(new Git(repo), new Git(repo).tags("v*"), t);
        assertEquals("v1", ranked.get(0).ref());
    }

    @Test
    void expectedAndEnclosing() {
        assertEquals("Svc", SourceMapper.expected(new StackTrace.Frame("a.Svc", "<init>", "Svc.java", 3, null)));
        assertEquals("run", SourceMapper.expected(new StackTrace.Frame("a.Svc$1", "run", "Svc.java", 3, null)));
        assertNull(SourceMapper.expected(new StackTrace.Frame("a.Svc$1", "<init>", "Svc.java", 3, null)));
        assertEquals("save", SourceMapper.expected(new StackTrace.Frame("a.Svc", "lambda$save$0", "Svc.java", 3, null)));
        String[] src = { "class A {", "  public <T> List<T> map(T x) {", "    process(x);", "    return x;", "  }", "}" };
        assertEquals("map", SourceMapper.enclosing(src, 4));
    }
}
