package io.github.elguardian.mcpsettools.callgraph.tool;

import io.github.elguardian.mcpsettools.tool.Tool;
import io.github.elguardian.mcpsettools.tool.Schema;
import io.github.elguardian.mcpsettools.tool.ToolArgs;
import io.github.elguardian.mcpsettools.tool.ToolException;
import io.github.elguardian.mcpsettools.tool.Results;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;

import io.github.elguardian.mcpsettools.analyze.GapReport;
import io.github.elguardian.mcpsettools.analyze.GraphQueries;
import io.github.elguardian.mcpsettools.analyze.MethodPattern;
import io.github.elguardian.mcpsettools.analyze.Model;
import io.github.elguardian.mcpsettools.analyze.detect.Detector;
import io.github.elguardian.mcpsettools.analyze.detect.Detectors;
import io.github.elguardian.mcpsettools.analyze.detect.Finding;
import io.github.elguardian.mcpsettools.analyze.report.SarifWriter;
import io.github.elguardian.mcpsettools.core.CallGraph;
import io.github.elguardian.mcpsettools.core.CallKind;
import io.github.elguardian.mcpsettools.core.Edge;
import io.github.elguardian.mcpsettools.core.MethodId;
import io.github.elguardian.mcpsettools.core.Provenance;
import io.github.elguardian.mcpsettools.core.io.CallGraphDot;
import io.github.elguardian.mcpsettools.core.io.CallGraphJson;
import io.github.elguardian.mcpsettools.core.trace.TraceSummary;
import io.github.elguardian.mcpsettools.stat.Algorithm;

/** Tools over loaded models: loading, method lookup, graph queries, gap report, detectors. */
final class AnalysisTools {

    static final String PATTERN_HELP = "Method pattern: pkg.Class.method with * wildcards, e.g. "
            + "'com.acme.*Dao.save*' or '*.Service.handle', optionally with a parameter list in Java form "
            + "'(int,java.lang.String)' or as a JVM descriptor '(ILjava/lang/String;)V'. A pattern without a "
            + "package that matches nothing is retried as '*.<pattern>'.";

    private enum DotLevel {
        DOT, DOT_CLASSES, JSON
    }

    private enum Sort {
        SELF, TOTAL, CALLS, EXCEPTIONS
    }

    private final Models models;

    AnalysisTools(Models models) {
        this.models = models;
    }

    List<Tool> tools() {
        return List.of(loadModel(), listModels(), unloadModel(), findMethods(), describeMethod(), callPaths(),
                checkGuard(), reach("callers"), reach("callees"), gapReport(), hotspots(), runDetectors(),
                listDetectors(), exportGraph());
    }

    private Tool loadModel() {
        return new Tool("load_model", "Load a program model", """
                Builds a model of a Java program and keeps it in memory for the other tools: a static \
                call graph from bytecode (app inputs), runtime call graphs and statistics from mcp-set-tools agent \
                recordings (trace inputs), or both merged, with every edge tagged static, runtime or \
                static+runtime. Static analysis of a large deployment can take a while; do it once and \
                query many times. Loading the same inputs again (e.g. after a new recording into the same \
                directory) refreshes the model under the same id. Returns the model_id and a summary.""",
                true, Schema.object()
                        .strings("app", "Application inputs whose method bodies are analyzed: jars, class "
                                + "directories, .class files, WAR/EAR/RAR archives", false)
                        .strings("lib", "Library inputs: complete the class hierarchy, bodies not analyzed. "
                                + "Pass third-party jars here", false)
                        .strings("trace", "Recording directories, or an agent out= directory holding several "
                                + "recordings", false)
                        .choice("algorithm", "Virtual call resolution: cha (every concrete subtype) or rta "
                                + "(only types instantiated in application code)", "cha", "cha", "rta")
                        .bool("jdk", "Load classes missing from the inputs from the server's JDK", true)
                        .build(),
                args -> {
                    Models.Inputs in = new Models.Inputs(args.paths("app"), args.paths("lib"), args.paths("trace"),
                            args.choice("algorithm", Algorithm.class, Algorithm.CHA), args.bool("jdk", true));
                    for (Path p : concat(in.app(), in.lib(), in.trace())) {
                        if (!Files.exists(p)) {
                            throw new ToolException("Input does not exist: " + p);
                        }
                    }
                    return summary(models.load(in, args.notifier()), true);
                });
    }

    private Tool listModels() {
        return new Tool("list_models", "List loaded models", "Lists the models loaded in this server with "
                + "their inputs and size.", true, Schema.object().build(),
                args -> Map.of("models", models.all().stream().map(l -> summary(l, false)).toList()));
    }

    private Tool unloadModel() {
        return new Tool("unload_model", "Unload a model", "Frees the memory of a loaded model.", false,
                Schema.object().string("model_id", "Model to unload", true).build(), args -> {
                    String id = args.require("model_id");
                    if (!models.remove(id)) {
                        throw new ToolException("Unknown model_id " + id);
                    }
                    return Map.of("unloaded", id);
                });
    }

    private Tool findMethods() {
        return new Tool("find_methods", "Find methods", """
                Looks up the methods of a model matching a pattern, to get exact names for the other tools. \
                Shows for each whether it is an application method, whether it was instrumented and \
                executed at runtime, its runtime call count and its number of direct callers/callees.""" + " "
                + PATTERN_HELP, true,
                Schema.object().model().string("pattern", PATTERN_HELP, true)
                        .integer("limit", "Maximum methods listed", 50).build(),
                args -> {
                    Model model = models.get(args).model();
                    String pattern = args.require("pattern");
                    List<MethodId> found = Models.resolve(model, pattern).stream().sorted().toList();
                    return Results.page(found.stream().map(id -> Views.method(model, id)).toList(),
                            args.integer("limit", 50));
                });
    }

    private Tool describeMethod() {
        return new Tool("describe_method", "Describe a method", """
                Everything known about one method: whether it is application code, instrumented and \
                executed, runtime statistics (calls, total/self time, recursion depth, exceptions that left \
                it, exceptions it caught) and its direct callers and callees with provenance, call kinds, \
                source lines and runtime call counts. The pattern must match exactly one method.""", true,
                Schema.object().model().string("method", PATTERN_HELP, true)
                        .integer("limit", "Maximum callers and callees listed each", 50).build(),
                args -> {
                    Model model = models.get(args).model();
                    String pattern = args.require("method");
                    List<MethodId> found = Models.resolve(model, pattern).stream().sorted().toList();
                    if (found.isEmpty()) {
                        throw Models.noMatch("method", pattern);
                    }
                    if (found.size() > 1) {
                        throw new ToolException("Pattern " + pattern + " matches " + found.size()
                                + " methods; narrow it, e.g. with a parameter list: " + found.stream().limit(20)
                                        .map(Views::name).collect(Collectors.joining(", "))
                                + (found.size() > 20 ? ", ..." : ""));
                    }
                    MethodId id = found.get(0);
                    Map<String, Object> m = Views.method(model, id);
                    TraceSummary.MethodStats s = model.runtime(id);
                    if (s != null) {
                        m.put("runtime", Views.stats(s));
                    }
                    CallGraph g = model.graph();
                    int limit = args.integer("limit", 50);
                    if (g.contains(id)) {
                        Comparator<Edge> order = Comparator.comparingLong(Edge::runtimeCount).reversed();
                        m.put("callers", Results.page(g.incoming(id).stream()
                                .sorted(order.thenComparing(Edge::caller)).map(e -> Views.edge(e, "caller"))
                                .toList(), limit));
                        m.put("callees", Results.page(g.outgoing(id).stream()
                                .sorted(order.thenComparing(Edge::callee)).map(e -> Views.edge(e, "callee"))
                                .toList(), limit));
                    }
                    return m;
                });
    }

    private Tool callPaths() {
        return new Tool("call_paths", "Find call paths", """
                Shortest call chains from any method matching 'from' to any method matching 'to' in the \
                merged call graph, each step with its provenance (static, runtime, static+runtime), \
                source lines and runtime count. Use edges=dynamic for chains that actually ran, \
                edges=static for what the bytecode allows.""", true,
                Schema.object().model().string("from", "Start methods. " + PATTERN_HELP, true)
                        .string("to", "Target methods. " + PATTERN_HELP, true).edges()
                        .integer("max_depth", "Longest path in calls", 20)
                        .integer("limit", "Maximum paths", 10).build(),
                args -> {
                    Model model = models.get(args).model();
                    GraphQueries q = queries(model, args);
                    int maxDepth = args.integer("max_depth", 20);
                    List<List<MethodId>> paths = q.paths(Models.select(model, args, "from"),
                            Models.select(model, args, "to"), maxDepth, args.integer("limit", 10));
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("found", paths.size());
                    m.put("paths", paths.stream().map(p -> Views.path(model.graph(), p)).toList());
                    if (paths.isEmpty()) {
                        m.put("note", "No path within " + maxDepth + " calls");
                    }
                    return m;
                });
    }

    private Tool checkGuard() {
        return new Tool("check_guard", "Check a guard", """
                Checks that every call chain from 'from' to 'to' passes through a 'through' method (e.g. a \
                security check), i.e. that the guard is a caller on the stack. Returns ok=true, or ok=false \
                with a counterexample path that avoids the guard. The call graph has no order within a \
                method body: a check made by the same method just before the guarded call \
                (check(); delete();) is a sibling, not a caller, and does not count.""", true,
                Schema.object().model().string("from", "Entry points. " + PATTERN_HELP, true)
                        .string("to", "Guarded methods. " + PATTERN_HELP, true)
                        .string("through", "Guard methods. " + PATTERN_HELP, true).edges().build(),
                args -> {
                    Model model = models.get(args).model();
                    Set<MethodId> from = Models.select(model, args, "from");
                    Set<MethodId> to = Models.select(model, args, "to");
                    Set<MethodId> through = Models.select(model, args, "through");
                    Optional<List<MethodId>> bad = queries(model, args).pathAvoiding(from, to, through);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("ok", bad.isEmpty());
                    m.put("from_methods", from.size());
                    m.put("to_methods", to.size());
                    m.put("through_methods", through.size());
                    bad.ifPresent(p -> m.put("counterexample", Views.path(model.graph(), p)));
                    return m;
                });
    }

    private Tool reach(String direction) {
        boolean callers = direction.equals("callers");
        String description = callers
                ? "Methods that transitively call the given methods, with their distance in calls (1 = direct "
                        + "caller). Answers 'who can reach this?' / impact analysis."
                : "Methods transitively called by the given methods, with their distance in calls (1 = direct "
                        + "callee). Answers 'what can this reach?'.";
        return new Tool(direction, callers ? "Transitive callers" : "Transitive callees", description, true,
                Schema.object().model().string("method", PATTERN_HELP, true).edges()
                        .integer("max_depth", "Maximum distance in calls", 20)
                        .integer("limit", "Maximum methods listed", 50).build(),
                args -> {
                    Model model = models.get(args).model();
                    Set<MethodId> start = Models.select(model, args, "method");
                    int maxDepth = args.integer("max_depth", 20);
                    GraphQueries q = queries(model, args);
                    Map<MethodId, Integer> found = callers ? q.callers(start, maxDepth) : q.callees(start, maxDepth);
                    List<Map<String, Object>> rows = found.entrySet().stream().filter(e -> e.getValue() > 0)
                            .sorted(Map.Entry.<MethodId, Integer>comparingByValue()
                                    .thenComparing(Map.Entry.comparingByKey()))
                            .map(e -> {
                                Map<String, Object> r = new LinkedHashMap<>();
                                r.put("method", Views.name(e.getKey()));
                                r.put("distance", e.getValue());
                                return r;
                            }).toList();
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("start_methods", start.stream().sorted().limit(10).map(Views::name).toList());
                    m.putAll(Results.page(rows, args.integer("limit", 50)));
                    return m;
                });
    }

    private Tool gapReport() {
        return new Tool("gap_report", "Static vs runtime gaps", """
                Where the static and runtime views disagree (needs recordings; the edge lists also need a \
                static analysis): runtime-only edges (no static call site: reflection, dynamic proxies, \
                EJB/CDI interceptors, callbacks through uninstrumented code), static edges never exercised \
                although the caller ran (untested branches, or CHA over-approximation), and instrumented \
                methods that never ran (test coverage gaps).""", true,
                Schema.object().model().integer("limit", "Maximum entries per list (-1: all)", 50).build(),
                args -> {
                    Model model = models.get(args).model();
                    if (!model.hasRuntime()) {
                        throw new ToolException("The gap report needs recordings: load the model with 'trace'");
                    }
                    GapReport r = GapReport.of(model);
                    int limit = args.integer("limit", 50);
                    Map<String, Object> m = new LinkedHashMap<>();
                    long ran = model.instrumented().stream().filter(model.executed()::contains).count();
                    m.put("instrumented_methods", model.instrumented().size());
                    m.put("executed_instrumented_methods", ran);
                    if (model.hasStatic()) {
                        m.put("runtime_only_edges", Results.page(r.runtimeOnlyEdges().stream()
                                .map(e -> Views.edge(e, null)).toList(), limit));
                        m.put("unexercised_static_edges", Results.page(r.unexercisedStaticEdges().stream()
                                .map(e -> Views.edge(e, null)).toList(), limit));
                    }
                    m.put("never_executed", Results.page(r.neverExecuted().stream().map(Views::name).toList(), limit));
                    return m;
                });
    }

    private Tool hotspots() {
        return new Tool("hotspots", "Runtime hotspots", """
                Methods ranked by runtime statistics summed over the recordings: self time (excluding \
                instrumented callees), total time, call count, or exceptional exits.""", true,
                Schema.object().model()
                        .choice("sort", "Ranking", "self", "self", "total", "calls", "exceptions")
                        .string("pattern", "Only methods matching this pattern. " + PATTERN_HELP, false)
                        .integer("limit", "Maximum methods listed", 30).build(),
                args -> {
                    Model model = models.get(args).model();
                    if (!model.hasRuntime()) {
                        throw new ToolException("Hotspots need recordings: load the model with 'trace'");
                    }
                    MethodPattern filter = args.has("pattern") ? Models.pattern(args.require("pattern")) : null;
                    ToLongFunction<TraceSummary.MethodStats> key = switch (args.choice("sort", Sort.class, Sort.SELF)) {
                        case SELF -> TraceSummary.MethodStats::selfNanos;
                        case TOTAL -> TraceSummary.MethodStats::totalNanos;
                        case CALLS -> TraceSummary.MethodStats::calls;
                        case EXCEPTIONS -> s -> s.exceptions().values().stream().mapToLong(Long::longValue).sum();
                    };
                    List<Map<String, Object>> rows = model.runtime().entrySet().stream()
                            .filter(e -> filter == null || filter.matches(e.getKey()))
                            .sorted(Comparator.comparingLong(
                                    (Map.Entry<MethodId, TraceSummary.MethodStats> e) -> key.applyAsLong(e.getValue()))
                                    .reversed().thenComparing(Map.Entry.comparingByKey()))
                            .map(e -> {
                                Map<String, Object> r = new LinkedHashMap<>();
                                r.put("method", Views.name(e.getKey()));
                                r.putAll(Views.stats(e.getValue()));
                                return r;
                            }).toList();
                    return Results.page(rows, args.integer("limit", 30));
                });
    }

    private Tool runDetectors() {
        return new Tool("run_detectors", "Run bug detectors", """
                Runs mcp-set-tools's bug detectors (null dereferences, resource leaks, swallowed exceptions, lock \
                order inversions/deadlocks, deep recursion, dead private methods, unexpected runtime \
                exceptions) over the model. Each finding has a severity (how bad if real), a confidence \
                (how likely it is real), a location and, when a recording backs it, runtime evidence; \
                runtime-confirmed findings come first. See list_detectors for the rules. Optionally also \
                writes a SARIF 2.1.0 report.""", false,
                Schema.object().model()
                        .strings("detectors", "Detector ids (default all): nulls, resources, exceptions, locks, "
                                + "recursion, dead-code", false)
                        .choice("min_severity", "Lowest severity reported", "low", "high", "medium", "low", "info")
                        .string("rule", "Only findings whose rule id starts with this, e.g. 'resource.' or "
                                + "'deadlock.lock-order'", false)
                        .integer("limit", "Maximum findings listed (-1: all)", 50)
                        .string("sarif_file", "Also write all findings (ignoring limit and rule) as SARIF to this "
                                + "file", false)
                        .string("source_root", "Prefix for SARIF source paths, e.g. src/main/java", false).build(),
                args -> {
                    Model model = models.get(args).model();
                    List<Detector> detectors;
                    try {
                        detectors = Detectors.select(args.strings("detectors"));
                    } catch (IllegalArgumentException e) {
                        throw new ToolException(e.getMessage());
                    }
                    Finding.Severity min = args.choice("min_severity", Finding.Severity.class, Finding.Severity.LOW);
                    List<Finding> findings = Detectors.atLeast(Detectors.run(model, detectors), min);
                    Map<String, Object> m = new LinkedHashMap<>();
                    if (args.has("sarif_file")) {
                        Path sarif = ToolArgs.path(args.require("sarif_file"));
                        Files.createDirectories(sarif.toAbsolutePath().getParent());
                        try (Writer w = Files.newBufferedWriter(sarif)) {
                            new SarifWriter(model, args.string("source_root", "")).write(findings,
                                    Detectors.rules(detectors), w);
                        }
                        m.put("sarif_file", sarif.toString());
                    }
                    String rule = args.string("rule", null);
                    List<Finding> shown = rule == null ? findings
                            : findings.stream().filter(f -> f.rule().startsWith(rule)).toList();
                    m.put("by_severity", count(shown, f -> Results.lower(f.severity())));
                    m.put("by_rule", count(shown, Finding::rule));
                    m.put("confirmed_at_runtime", shown.stream().filter(Finding::confirmedAtRuntime).count());
                    m.putAll(Results.page(shown.stream().map(Views::finding).toList(), args.integer("limit", 50)));
                    if (!model.hasRuntime()) {
                        m.put("note", "Static analysis only: runtime rules (runtime.*, deadlock.lock-order, "
                                + "recursion.deep) need recordings");
                    } else if (!model.hasStatic()) {
                        m.put("note", "Recordings only: static rules need 'app' inputs");
                    }
                    return m;
                });
    }

    private Tool listDetectors() {
        return new Tool("list_detectors", "List bug detectors", "Lists the bug detectors and the rules each "
                + "reports, with a one-line description of each rule.", true, Schema.object().build(),
                args -> Map.of("detectors", Detectors.all().stream().map(d -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", d.id());
                    m.put("rules", d.rules());
                    return m;
                }).toList()));
    }

    private Tool exportGraph() {
        return new Tool("export_graph", "Export the call graph", """
                Writes the merged call graph of a model to a file: json (methods and edges with kinds, lines \
                and provenance), dot (Graphviz, one node per method) or dot_classes (one node per class). \
                Optionally restricted to the methods reachable from, or reaching, a pattern.""", false,
                Schema.object().model().string("output", "File to write", true)
                        .choice("format", "Output format", "json", "json", "dot", "dot_classes")
                        .string("from", "Only methods reachable from these. " + PATTERN_HELP, false)
                        .string("to", "Only methods that reach these. " + PATTERN_HELP, false).edges()
                        .integer("max_depth", "Distance limit for from/to", 20).build(),
                args -> {
                    Model model = models.get(args).model();
                    CallGraph graph = model.graph();
                    if (args.has("from") || args.has("to")) {
                        graph = subgraph(model, args);
                    }
                    Path out = ToolArgs.path(args.require("output"));
                    Files.createDirectories(out.toAbsolutePath().getParent());
                    DotLevel format = args.choice("format", DotLevel.class, DotLevel.JSON);
                    try (Writer w = Files.newBufferedWriter(out)) {
                        switch (format) {
                            case JSON -> CallGraphJson.write(graph, w);
                            case DOT -> CallGraphDot.write(graph, w, CallGraphDot.Level.METHOD);
                            case DOT_CLASSES -> CallGraphDot.write(graph, w, CallGraphDot.Level.CLASS);
                        }
                    }
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("output", out.toString());
                    m.put("methods", graph.methods().size());
                    m.put("edges", graph.edgeCount());
                    return m;
                });
    }

    /** The edges between methods within reach of 'from' and/or 'to'. */
    private CallGraph subgraph(Model model, ToolArgs args) {
        GraphQueries q = queries(model, args);
        int maxDepth = args.integer("max_depth", 20);
        Set<MethodId> keep = null;
        if (args.has("from")) {
            keep = q.callees(Models.select(model, args, "from"), maxDepth).keySet();
        }
        if (args.has("to")) {
            Set<MethodId> reaching = q.callers(Models.select(model, args, "to"), maxDepth).keySet();
            keep = keep == null ? reaching : keep.stream().filter(reaching::contains).collect(Collectors.toSet());
        }
        GraphQueries.Edges edges = args.choice("edges", GraphQueries.Edges.class, GraphQueries.Edges.ALL);
        Set<MethodId> nodes = keep;
        CallGraph sub = new CallGraph();
        nodes.forEach(sub::addMethod);
        model.graph().edges().filter(e -> nodes.contains(e.caller()) && nodes.contains(e.callee()))
                .filter(e -> switch (edges) {
                    case ALL -> true;
                    case STATIC -> e.provenance() != Provenance.DYNAMIC;
                    case DYNAMIC -> e.provenance() != Provenance.STATIC;
                }).forEach(e -> copy(e, sub));
        return sub;
    }

    private static void copy(Edge e, CallGraph into) {
        boolean statically = e.provenance() != Provenance.DYNAMIC;
        if (statically) {
            for (CallKind kind : e.kinds()) {
                if (kind == CallKind.RUNTIME) {
                    continue;
                }
                if (e.lines().isEmpty()) {
                    into.addStaticCall(e.caller(), e.callee(), kind, -1);
                } else {
                    e.lines().forEach(line -> into.addStaticCall(e.caller(), e.callee(), kind, line));
                }
            }
        }
        if (e.provenance() != Provenance.STATIC) {
            into.addRuntimeCall(e.caller(), e.callee(), e.runtimeCount());
        }
    }

    private Map<String, Object> summary(Models.Loaded l, boolean full) {
        Model model = l.model();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("model_id", l.id());
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("app", l.inputs().app().stream().map(Path::toString).toList());
        if (!l.inputs().lib().isEmpty()) {
            inputs.put("lib", l.inputs().lib().stream().map(Path::toString).toList());
        }
        inputs.put("trace", l.inputs().trace().stream().map(Path::toString).toList());
        inputs.put("algorithm", Results.lower(l.inputs().algorithm()));
        m.put("inputs", inputs);
        Map<String, Long> byProvenance = new TreeMap<>(model.graph().edges()
                .collect(Collectors.groupingBy(e -> Views.provenance(e.provenance()), Collectors.counting())));
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("methods", model.graph().methods().size());
        graph.put("edges", model.graph().edgeCount());
        graph.put("edges_by_provenance", byProvenance);
        m.put("graph", graph);
        if (!full) {
            return m;
        }
        m.put("load_seconds", Math.round(l.took().toMillis() / 100.0) / 10.0);
        if (model.hasStatic()) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("app_classes", model.statics().appClasses().size());
            s.put("call_sites", model.statics().callSites().size());
            m.put("static", s);
        }
        if (model.hasRuntime()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("recordings", model.traces().stream().map(t -> {
                Map<String, Object> rec = new LinkedHashMap<>();
                rec.put("label", t.recording().label());
                rec.put("directory", t.recording().directory().toString());
                rec.put("complete", t.recording().isComplete());
                rec.put("threads", t.recording().threads().size());
                return rec;
            }).toList());
            r.put("instrumented_methods", model.instrumented().size());
            r.put("executed_methods", model.executed().size());
            m.put("runtime", r);
        }
        if (!l.warnings().isEmpty()) {
            m.put("warnings", Results.page(l.warnings(), 20));
        }
        return m;
    }

    private static GraphQueries queries(Model model, ToolArgs args) {
        return new GraphQueries(model.graph(), args.choice("edges", GraphQueries.Edges.class, GraphQueries.Edges.ALL));
    }

    private static <T> Map<String, Long> count(List<T> items, java.util.function.Function<T, String> key) {
        return items.stream().collect(Collectors.groupingBy(key, LinkedHashMap::new, Collectors.counting()));
    }

    @SafeVarargs
    private static <T> List<T> concat(List<T>... lists) {
        List<T> all = new ArrayList<>();
        for (List<T> l : lists) {
            all.addAll(l);
        }
        return all;
    }
}
