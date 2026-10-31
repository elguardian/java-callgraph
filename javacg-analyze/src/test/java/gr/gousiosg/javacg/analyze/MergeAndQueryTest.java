package gr.gousiosg.javacg.analyze;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringWriter;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import gr.gousiosg.javacg.core.MethodId;
import gr.gousiosg.javacg.core.Provenance;
import gr.gousiosg.javacg.rt.Probe;
import gr.gousiosg.javacg.rt.Recorder;
import gr.gousiosg.javacg.rt.Symbols;
import gr.gousiosg.javacg.stat.MethodInfo;
import gr.gousiosg.javacg.stat.StaticAnalyzer;
import gr.gousiosg.javacg.stat.StaticResult;
import gr.gousiosg.javacg.testkit.TestCompiler;

class MergeAndQueryTest {

    private static final MethodId HANDLE = m("q/Svc", "handle", "(Ljava/lang/String;)V");
    private static final MethodId CHECK = m("q/Svc", "check", "()V");
    private static final MethodId ADMIN = m("q/Svc", "admin", "()V");
    private static final MethodId NORMAL = m("q/Svc", "normal", "()V");
    private static final MethodId REFLECT = m("q/Svc", "reflect", "()V");
    private static final MethodId HIDDEN = m("q/Svc", "hidden", "()V");
    private static final MethodId SAVE = m("q/Dao", "save", "()V");

    static Model model;

    private static MethodId m(String owner, String name, String desc) {
        return MethodId.of(owner, name, desc);
    }

    @BeforeAll
    static void setUp(@TempDir Path tmp) throws Exception {
        Path jar = TestCompiler.create().source("q.Svc", """
                package q;
                public class Svc {
                    public void handle(String op) {
                        if (op.equals("admin")) { check(); admin(); }
                        else if (op.equals("debug")) { admin(); }
                        else { normal(); }
                    }
                    void check() { }
                    void admin() { Dao.save(); }
                    void normal() { }
                    void reflect() throws Exception { Svc.class.getDeclaredMethod("hidden").invoke(this); }
                    void hidden() { }
                    void unused() { }
                }
                class Dao { static void save() { } }
                """).compile().writeJar(tmp.resolve("q.jar"));
        StaticResult statics = StaticAnalyzer.create().app(jar).run();

        // what the agent would record for: svc.handle("other"); svc.reflect();
        Recorder.configure(tmp.resolve("out").toString(), "none", false);
        Recorder.start("run");
        for (var c : statics.appClasses()) {
            for (MethodInfo mi : c.methods()) {
                Symbols.method(mi.id().owner(), mi.id().name(), mi.id().descriptor());
            }
        }
        call(HANDLE, () -> call(NORMAL, () -> { }));
        call(REFLECT, () -> call(HIDDEN, () -> { }));
        Recorder.stop();

        model = Model.of(statics, Model.loadRecordings(List.of(tmp.resolve("out"))));
    }

    private static void call(MethodId m, Runnable body) {
        int id = Symbols.method(m.owner(), m.name(), m.descriptor());
        Probe.enter(id);
        body.run();
        Probe.exit(id);
    }

    @Test
    void mergedEdgesCarryProvenance() {
        assertEquals(Provenance.BOTH, model.graph().edge(HANDLE, NORMAL).provenance());
        assertEquals(Provenance.STATIC, model.graph().edge(HANDLE, CHECK).provenance());
        assertEquals(Provenance.DYNAMIC, model.graph().edge(REFLECT, HIDDEN).provenance());
    }

    @Test
    void gapReportFindsReflectionAndUntestedBranches() {
        GapReport gaps = GapReport.of(model);
        assertEquals(1, gaps.runtimeOnlyEdges().size());
        assertEquals(HIDDEN, gaps.runtimeOnlyEdges().get(0).callee());
        assertEquals(Set.of(CHECK, ADMIN), Set.copyOf(gaps.unexercisedStaticEdges().stream().map(e -> e.callee()).toList()));
        assertTrue(gaps.neverExecuted().containsAll(List.of(CHECK, ADMIN, SAVE, m("q/Svc", "unused", "()V"))));
        assertFalse(gaps.neverExecuted().contains(HANDLE));

        StringWriter w = new StringWriter();
        gaps.write(w, 1);
        String text = w.toString();
        assertTrue(text.contains("q.Svc.reflect() -> q.Svc.hidden()  x1"), text);
        assertTrue(text.contains("... "), text);
    }

    @Test
    void pathAndGuardQueries() {
        GraphQueries q = new GraphQueries(model.graph(), GraphQueries.Edges.ALL);
        assertEquals(List.of(List.of(HANDLE, ADMIN, SAVE)), q.paths(Set.of(HANDLE), Set.of(SAVE), 10, 10));
        assertTrue(q.paths(Set.of(HANDLE), Set.of(SAVE), 1, 10).isEmpty(), "maxEdges bounds the search");

        // "is every path from handle() to Dao.save() guarded by check()?" -- no: the debug branch
        assertEquals(List.of(HANDLE, ADMIN, SAVE), q.pathAvoiding(Set.of(HANDLE), Set.of(SAVE), Set.of(CHECK)).orElseThrow());
        assertTrue(q.pathAvoiding(Set.of(HANDLE), Set.of(SAVE), Set.of(ADMIN)).isEmpty());

        Map<MethodId, Integer> callers = q.callers(Set.of(SAVE), 5);
        assertEquals(1, callers.get(ADMIN));
        assertEquals(2, callers.get(HANDLE));

        GraphQueries runtimeOnly = new GraphQueries(model.graph(), GraphQueries.Edges.DYNAMIC);
        assertEquals(Set.of(HANDLE, NORMAL), runtimeOnly.callees(Set.of(HANDLE), 5).keySet());
        assertTrue(new GraphQueries(model.graph(), GraphQueries.Edges.STATIC).callees(Set.of(REFLECT), 5)
                .keySet().stream().noneMatch(HIDDEN::equals));
    }

    @Test
    void methodPatterns() {
        assertTrue(MethodPattern.parse("q.Svc.handle").matches(HANDLE));
        assertTrue(MethodPattern.parse("q.*.save").matches(SAVE));
        assertTrue(MethodPattern.parse("*.Svc.h*").matches(HANDLE));
        assertTrue(MethodPattern.parse("*.Svc.h*").matches(HIDDEN));
        assertTrue(MethodPattern.parse("q.Svc.handle(java.lang.String)").matches(HANDLE));
        assertTrue(MethodPattern.parse("q/Svc.handle(Ljava/lang/String;)V").matches(HANDLE));
        assertTrue(MethodPattern.parse("q.Svc.handle(Ljava/lang/String;)").matches(HANDLE));
        assertFalse(MethodPattern.parse("q.Svc.handle(int)").matches(HANDLE));
        assertFalse(MethodPattern.parse("Svc.handle").matches(HANDLE), "class names are qualified");
        assertEquals(Set.of(CHECK), MethodPattern.parse("q.Svc.check").select(model.graph().methods()));
    }

    @Test
    void recordingDirectoriesMustExist(@TempDir Path empty) {
        assertThrows(NoSuchFileException.class, () -> Model.loadRecordings(List.of(empty)));
        assertThrows(NoSuchFileException.class, () -> Model.loadRecordings(List.of(empty.resolve("nope"))));
    }
}
