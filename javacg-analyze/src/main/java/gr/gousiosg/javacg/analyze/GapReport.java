package gr.gousiosg.javacg.analyze;

import java.io.PrintWriter;
import java.io.Writer;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import gr.gousiosg.javacg.core.Edge;
import gr.gousiosg.javacg.core.MethodId;
import gr.gousiosg.javacg.core.Provenance;

/**
 * Where the static and the runtime view disagree:
 * <ul>
 * <li>runtime-only edges — calls with no static call site: reflection, dynamic proxies,
 * interceptors, or callbacks through code that was not instrumented;</li>
 * <li>unexercised static edges — the caller ran, but never made this call (an untested
 * branch, or an edge CHA over-approximated);</li>
 * <li>instrumented methods that never ran.</li>
 * </ul>
 */
public final class GapReport {

    private final Model model;
    private final List<Edge> runtimeOnly;
    private final List<Edge> unexercised;
    private final List<MethodId> neverExecuted;

    private GapReport(Model model) {
        this.model = model;
        Comparator<Edge> byCount = Comparator.comparingLong(Edge::runtimeCount).reversed()
                .thenComparing(Edge::caller).thenComparing(Edge::callee);
        this.runtimeOnly = model.graph().edges().filter(e -> e.provenance() == Provenance.DYNAMIC).sorted(byCount)
                .collect(Collectors.toList());
        this.unexercised = model.graph().edges()
                .filter(e -> e.provenance() == Provenance.STATIC && model.executed().contains(e.caller())
                        && model.instrumented().contains(e.callee()))
                .sorted(Comparator.comparing(Edge::caller).thenComparing(Edge::callee)).collect(Collectors.toList());
        this.neverExecuted = model.instrumented().stream().filter(m -> !model.executed().contains(m)).sorted()
                .collect(Collectors.toList());
    }

    public static GapReport of(Model model) {
        return new GapReport(model);
    }

    public List<Edge> runtimeOnlyEdges() {
        return runtimeOnly;
    }

    public List<Edge> unexercisedStaticEdges() {
        return unexercised;
    }

    public List<MethodId> neverExecuted() {
        return neverExecuted;
    }

    /** Writes the report; each list is cut at {@code limit} entries (negative: no limit). */
    public void write(Writer out, int limit) {
        PrintWriter w = new PrintWriter(out);
        if (model.hasStatic()) {
            w.printf("Static analysis: %d application classes, %d call sites, algorithm %s%n",
                    model.statics().appClasses().size(), model.statics().callSites().size(),
                    model.statics().algorithm());
        }
        if (model.hasRuntime()) {
            long instrumented = model.instrumented().size();
            long ran = model.instrumented().stream().filter(model.executed()::contains).count();
            w.printf("Runtime: %d recording(s), %d of %d instrumented methods executed (%.1f%%)%n",
                    model.traces().size(), ran, instrumented, instrumented == 0 ? 0.0 : 100.0 * ran / instrumented);
        }
        w.printf("Merged graph: %d methods, %d edges%n", model.graph().methods().size(), model.graph().edgeCount());
        if (model.hasStatic() && model.hasRuntime()) {
            section(w, "Runtime-only edges (no static call site: reflection, proxies, interceptors, "
                    + "or calls through uninstrumented code)", runtimeOnly.stream()
                    .map(e -> e.caller().javaName() + " -> " + e.callee().javaName() + "  x" + e.runtimeCount())
                    .toList(), limit);
            section(w, "Static edges never exercised although the caller ran", unexercised.stream()
                    .map(e -> e.caller().javaName() + " -> " + e.callee().javaName() + linesOf(e)).toList(), limit);
        }
        if (model.hasRuntime()) {
            section(w, "Instrumented methods that never ran", neverExecuted.stream().map(MethodId::javaName).toList(),
                    limit);
        }
        w.flush();
    }

    private static String linesOf(Edge e) {
        return e.lines().isEmpty() ? "" : "  (line " + e.lines().stream().map(String::valueOf)
                .collect(Collectors.joining(", ")) + ")";
    }

    private static void section(PrintWriter w, String title, List<String> items, int limit) {
        w.printf("%n%s: %d%n", title, items.size());
        int n = limit < 0 ? items.size() : Math.min(limit, items.size());
        for (int i = 0; i < n; i++) {
            w.println("  " + items.get(i));
        }
        if (n < items.size()) {
            w.printf("  ... %d more (use --limit)%n", items.size() - n);
        }
    }
}
