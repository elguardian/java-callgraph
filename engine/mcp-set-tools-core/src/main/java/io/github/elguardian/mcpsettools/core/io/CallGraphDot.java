package io.github.elguardian.mcpsettools.core.io;

import java.io.PrintWriter;
import java.io.Writer;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import io.github.elguardian.mcpsettools.core.CallGraph;
import io.github.elguardian.mcpsettools.core.Edge;
import io.github.elguardian.mcpsettools.core.Provenance;

/**
 * Writes a {@link CallGraph} in Graphviz DOT. Edge style encodes provenance: dashed = static
 * only, bold red = runtime only, solid = both.
 */
public final class CallGraphDot {

    /** Granularity of the nodes. */
    public enum Level {
        METHOD, CLASS
    }

    private CallGraphDot() {
    }

    public static void write(CallGraph graph, Writer out, Level level) {
        PrintWriter w = new PrintWriter(out);
        w.println("digraph callgraph {");
        w.println("  rankdir=LR;");
        w.println("  node [shape=box, fontname=\"monospace\", fontsize=10];");
        if (level == Level.METHOD) {
            for (Edge e : graph.sortedEdges()) {
                w.println("  " + quote(e.caller().javaName()) + " -> " + quote(e.callee().javaName())
                        + style(e.provenance(), e.runtimeCount()) + ";");
            }
        } else {
            Map<String, Map<String, Provenance>> classEdges = new TreeMap<>();
            Map<String, Map<String, Long>> counts = new TreeMap<>();
            for (Edge e : graph.edges().toList()) {
                String from = e.caller().className();
                String to = e.callee().className();
                if (from.equals(to)) {
                    continue;
                }
                classEdges.computeIfAbsent(from, k -> new TreeMap<>()).merge(to, e.provenance(), CallGraphDot::union);
                counts.computeIfAbsent(from, k -> new TreeMap<>()).merge(to, e.runtimeCount(), Long::sum);
            }
            classEdges.forEach((from, tos) -> new TreeSet<>(tos.keySet()).forEach(to -> w.println("  " + quote(from)
                    + " -> " + quote(to) + style(tos.get(to), counts.get(from).get(to)) + ";")));
        }
        w.println("}");
        w.flush();
    }

    private static Provenance union(Provenance a, Provenance b) {
        return a == b ? a : Provenance.BOTH;
    }

    private static String style(Provenance p, long count) {
        String label = count > 0 ? ", label=\"" + count + "\"" : "";
        return switch (p) {
            case STATIC -> " [style=dashed" + label + "]";
            case DYNAMIC -> " [color=red, style=bold" + label + "]";
            case BOTH -> label.isEmpty() ? "" : " [" + label.substring(2) + "]";
        };
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
