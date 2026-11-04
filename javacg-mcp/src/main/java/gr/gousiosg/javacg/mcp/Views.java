package gr.gousiosg.javacg.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import gr.gousiosg.javacg.analyze.Model;
import gr.gousiosg.javacg.analyze.detect.Finding;
import gr.gousiosg.javacg.core.CallGraph;
import gr.gousiosg.javacg.core.CallKind;
import gr.gousiosg.javacg.core.Edge;
import gr.gousiosg.javacg.core.MethodId;
import gr.gousiosg.javacg.core.Provenance;
import gr.gousiosg.javacg.core.trace.TraceSummary;
import gr.gousiosg.javacg.devtools.table.Table;

/**
 * JSON views of the model types. Methods are always written in the Java form
 * {@code a.b.C.name(int,java.lang.String)}, which is also a valid pattern for every tool that
 * takes one, so results can be fed back as arguments.
 */
final class Views {

    private Views() {
    }

    static String name(MethodId m) {
        return m == null ? null : m.javaName();
    }

    static String provenance(Provenance p) {
        return switch (p) {
            case STATIC -> "static";
            case DYNAMIC -> "runtime";
            case BOTH -> "static+runtime";
        };
    }

    static String lower(Enum<?> e) {
        return e.name().toLowerCase(Locale.ROOT);
    }

    /** An edge; {@code endpoint} names the method on the other side ("caller", "callee") or null for both. */
    static Map<String, Object> edge(Edge e, String endpoint) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (endpoint == null || endpoint.equals("caller")) {
            m.put("caller", name(e.caller()));
        }
        if (endpoint == null || endpoint.equals("callee")) {
            m.put("callee", name(e.callee()));
        }
        edgeDetails(e, m);
        return m;
    }

    private static void edgeDetails(Edge e, Map<String, Object> m) {
        m.put("provenance", provenance(e.provenance()));
        List<String> kinds = e.kinds().stream().filter(k -> k != CallKind.RUNTIME).map(Views::lower).toList();
        if (!kinds.isEmpty()) {
            m.put("kinds", kinds);
        }
        if (!e.lines().isEmpty()) {
            m.put("lines", List.copyOf(e.lines()));
        }
        if (e.runtimeCount() > 0) {
            m.put("runtime_calls", e.runtimeCount());
        }
    }

    /** A call path: the first method, then each step with the edge that reaches it. */
    static Map<String, Object> path(CallGraph graph, List<MethodId> path) {
        List<Object> steps = new ArrayList<>();
        steps.add(Map.of("method", name(path.get(0))));
        for (int i = 1; i < path.size(); i++) {
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("method", name(path.get(i)));
            edgeDetails(graph.edge(path.get(i - 1), path.get(i)), step);
            steps.add(step);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("calls", path.size() - 1);
        m.put("steps", steps);
        return m;
    }

    static Map<String, Object> stats(TraceSummary.MethodStats s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("calls", s.calls());
        m.put("total_ms", millis(s.totalNanos()));
        m.put("self_ms", millis(s.selfNanos()));
        m.put("max_recursion", s.maxRecursion());
        if (!s.exceptions().isEmpty()) {
            m.put("exceptional_exits", s.exceptions());
        }
        if (!s.thrownHere().isEmpty()) {
            m.put("thrown_here", s.thrownHere());
        }
        if (!s.caught().isEmpty()) {
            m.put("caught", s.caught());
        }
        return m;
    }

    /** Where a method stands in the model. */
    static Map<String, Object> method(Model model, MethodId id) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("method", name(id));
        m.put("descriptor", id.owner() + "." + id.name() + id.descriptor());
        m.put("app_method", model.hasStatic() && model.statics().appMethod(id) != null);
        if (model.hasRuntime()) {
            m.put("instrumented", model.instrumented().contains(id));
            m.put("executed", model.executed().contains(id));
        }
        CallGraph g = model.graph();
        if (g.contains(id)) {
            m.put("direct_callers", g.incoming(id).size());
            m.put("direct_callees", g.outgoing(id).size());
        }
        TraceSummary.MethodStats s = model.runtime(id);
        if (s != null) {
            m.put("runtime_calls", s.calls());
        }
        return m;
    }

    static Map<String, Object> finding(Finding f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rule", f.rule());
        m.put("severity", lower(f.severity()));
        m.put("confidence", lower(f.confidence()));
        m.put("message", f.message());
        if (f.method() != null) {
            m.put("method", name(f.method()));
        }
        if (f.line() > 0) {
            m.put("line", f.line());
        }
        if (!f.related().isEmpty()) {
            m.put("related", f.related().stream().map(l -> {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("method", name(l.method()));
                if (l.line() > 0) {
                    r.put("line", l.line());
                }
                if (l.note() != null) {
                    r.put("note", l.note());
                }
                return r;
            }).toList());
        }
        m.put("confirmed_at_runtime", f.confirmedAtRuntime());
        if (!f.evidence().isEmpty()) {
            m.put("evidence", f.evidence());
        }
        return m;
    }

    /** The first {@code limit} items as {@code {total, items, truncated?}}; negative limit: all. */
    static Map<String, Object> page(List<?> items, int limit) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", items.size());
        int n = limit < 0 ? items.size() : Math.min(limit, items.size());
        m.put("items", items.subList(0, n));
        if (n < items.size()) {
            m.put("truncated", true);
        }
        return m;
    }

    /**
     * Adds {@code table}, the rows rendered in the requested {@code table_format}, when the
     * caller asked for one. {@code columns} null takes every key of the rows.
     */
    static void table(Map<String, Object> into, List<? extends Map<String, ?>> rows, List<String> columns, ToolArgs args) {
        if (!args.has("table_format")) {
            return;
        }
        Table.Format format;
        try {
            format = Table.Format.parse(args.require("table_format"));
        } catch (IllegalArgumentException e) {
            throw new ToolException("'table_format' must be one of " + List.of(Schema.TABLE_FORMATS));
        }
        if (rows.isEmpty()) {
            into.put("table", "");
            return;
        }
        List<Map<String, Object>> flat = rows.stream().map(Views::flatten).toList();
        into.put("table", Table.fromMaps(flat, columns).maxWidth(args.integer("table_max_width", 80)).render(format));
    }

    /** Lists become comma-separated text and nested maps {@code k=v} pairs, so a row fits a table. */
    private static Map<String, Object> flatten(Map<String, ?> row) {
        Map<String, Object> m = new LinkedHashMap<>();
        row.forEach((k, v) -> m.put(k, v instanceof List<?> l ? String.join(", ", l.stream().map(String::valueOf).toList())
                : v instanceof Map<?, ?> mm ? mm.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                        .collect(java.util.stream.Collectors.joining(", "))
                        : v));
        return m;
    }

    static double millis(long nanos) {
        return Math.round(nanos / 10_000.0) / 100.0;
    }
}
