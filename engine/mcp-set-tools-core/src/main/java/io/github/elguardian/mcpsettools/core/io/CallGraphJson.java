package io.github.elguardian.mcpsettools.core.io;

import io.github.elguardian.mcpsettools.commons.json.JsonWriter;
import java.io.Writer;

import io.github.elguardian.mcpsettools.core.CallGraph;
import io.github.elguardian.mcpsettools.core.CallKind;
import io.github.elguardian.mcpsettools.core.Edge;
import io.github.elguardian.mcpsettools.core.MethodId;

/**
 * Writes a {@link CallGraph} as JSON:
 *
 * <pre>
 * {
 *   "methods": ["a/B.m()V", ...],
 *   "edges": [{"caller": "...", "callee": "...", "provenance": "STATIC",
 *              "kinds": ["VIRTUAL"], "lines": [12], "count": 0}, ...]
 * }
 * </pre>
 */
public final class CallGraphJson {

    private CallGraphJson() {
    }

    public static void write(CallGraph graph, Writer out) {
        JsonWriter json = new JsonWriter(out);
        json.beginObject();
        json.name("methods").beginArray();
        graph.methods().stream().sorted().map(MethodId::toString).forEach(json::value);
        json.endArray();
        json.name("edges").beginArray();
        for (Edge e : graph.sortedEdges()) {
            json.beginObject()
                    .field("caller", e.caller().toString())
                    .field("callee", e.callee().toString())
                    .field("provenance", e.provenance().name());
            json.name("kinds").beginArray();
            for (CallKind k : e.kinds()) {
                json.value(k.name());
            }
            json.endArray();
            json.name("lines").beginArray();
            for (int line : e.lines()) {
                json.value(line);
            }
            json.endArray();
            json.field("count", e.runtimeCount()).endObject();
        }
        json.endArray();
        json.endObject();
        json.flush();
    }
}
