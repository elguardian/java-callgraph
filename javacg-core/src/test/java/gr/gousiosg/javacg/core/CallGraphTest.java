package gr.gousiosg.javacg.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringWriter;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import gr.gousiosg.javacg.core.io.CallGraphJson;
import gr.gousiosg.javacg.commons.json.JsonWriter;

class CallGraphTest {

    private static final MethodId A = MethodId.of("p/A", "a", "()V");
    private static final MethodId B = MethodId.of("p/B", "b", "(ILjava/lang/String;)V");
    private static final MethodId C = MethodId.of("p/C$Inner", "<init>", "()V");

    @Test
    void methodIdRoundTripsThroughItsStringForm() {
        assertEquals("p/B.b(ILjava/lang/String;)V", B.toString());
        assertEquals(B, MethodId.parse(B.toString()));
        assertEquals("p.B.b(int,java.lang.String)", B.javaName());
        assertEquals("p.C$Inner", C.className());
        assertThrows(IllegalArgumentException.class, () -> MethodId.parse("noParens"));
    }

    @Test
    void edgesAggregateCallSitesAndRuntimeCounts() {
        CallGraph g = new CallGraph();
        g.addStaticCall(A, B, CallKind.VIRTUAL, 10);
        g.addStaticCall(A, B, CallKind.VIRTUAL, 12);
        g.addRuntimeCall(A, C, 3);

        assertEquals(2, g.edgeCount());
        assertEquals(Set.of(10, 12), g.edge(A, B).lines());
        assertEquals(Provenance.STATIC, g.edge(A, B).provenance());
        assertEquals(Provenance.DYNAMIC, g.edge(A, C).provenance());
        assertEquals(Set.of(B, C), g.callees(A));
        assertEquals(Set.of(A), g.callers(B));
        assertEquals(1, g.incoming(C).size());
    }

    @Test
    void mergeCombinesProvenance() {
        CallGraph statik = new CallGraph();
        statik.addStaticCall(A, B, CallKind.STATIC, 5);
        CallGraph dynamic = new CallGraph();
        dynamic.addRuntimeCall(A, B, 7);
        dynamic.addRuntimeCall(B, C, 1);

        statik.merge(dynamic);
        assertEquals(Provenance.BOTH, statik.edge(A, B).provenance());
        assertEquals(7, statik.edge(A, B).runtimeCount());
        assertEquals(Provenance.DYNAMIC, statik.edge(B, C).provenance());
        assertEquals(3, statik.methods().size());
    }

    @Test
    void jsonOutputIsDeterministicAndEscaped() {
        CallGraph g = new CallGraph();
        g.addStaticCall(B, A, CallKind.STATIC, 0);
        g.addStaticCall(A, B, CallKind.INTERFACE, 3);
        StringWriter w = new StringWriter();
        CallGraphJson.write(g, w);
        String json = w.toString();
        assertTrue(json.indexOf("\"caller\": \"p/A.a()V\"") < json.indexOf("\"caller\": \"p/B"), json);

        StringWriter w2 = new StringWriter();
        try (JsonWriter jw = new JsonWriter(w2)) {
            jw.beginArray().value("q\"\n\u0001").beginObject().endObject().beginArray().endArray().endArray();
        }
        assertEquals(List.of("[", "  \"q\\\"\\n\\u0001\",", "  {},", "  []", "]"), w2.toString().lines().toList());
    }
}
