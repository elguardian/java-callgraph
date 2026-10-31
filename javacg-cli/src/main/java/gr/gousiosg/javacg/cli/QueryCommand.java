package gr.gousiosg.javacg.cli;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import gr.gousiosg.javacg.analyze.GraphQueries;
import gr.gousiosg.javacg.analyze.Model;
import gr.gousiosg.javacg.core.CallGraph;
import gr.gousiosg.javacg.core.Edge;
import gr.gousiosg.javacg.core.MethodId;

/** {@code javacg query}: reachability questions over the merged graph. */
final class QueryCommand implements Command {

    /** Exit code when {@code guard} finds an unguarded path. */
    static final int VIOLATION = 3;

    @Override
    public String name() {
        return "query";
    }

    @Override
    public String summary() {
        return "Call paths, guard checks, transitive callers/callees";
    }

    @Override
    public String usage() {
        return """
                Usage: javacg query <paths|guard|callers|callees> [options]

                  paths    --from P --to Q        shortest call paths from P to Q
                  guard    --from P --to Q --through G
                                                  does every path from P to Q pass through G?
                                                  prints an unguarded path and exits 3 if not
                  callers  --method P             methods that (transitively) call P
                  callees  --method P             methods (transitively) called by P

                Method patterns: pkg.Class.method, with * wildcards and an optional
                (java.types) or (JVM descriptor) suffix, e.g. 'com.acme.*Dao.save*'.

                """ + ModelInputs.USAGE + """
                  --edges all|static|dynamic   edges to follow (default all)
                  --max-depth <n>      longest path / walk in calls (default 20)
                  --limit <n>          maximum paths or results (default 20)
                """;
    }

    @Override
    public int run(List<String> argv, PrintStream out, PrintStream err) throws Exception {
        if (argv.isEmpty()) {
            throw new Args.UsageException("Missing query type");
        }
        String type = argv.get(0);
        Args a = Args.parse(argv.subList(1, argv.size()), ModelInputs.flags(),
                ModelInputs.valued("from", "to", "through", "method", "edges", "max-depth", "limit"));
        if (!List.of("paths", "guard", "callers", "callees").contains(type)) {
            throw new Args.UsageException("Unknown query type " + type);
        }
        GraphQueries.Edges edges = a.choice("edges", GraphQueries.Edges.class, GraphQueries.Edges.ALL);
        int maxDepth = ModelInputs.intOption(a, "max-depth", 20);
        int limit = ModelInputs.intOption(a, "limit", 20);
        Model model = ModelInputs.load(a, err);
        GraphQueries q = new GraphQueries(model.graph(), edges);
        switch (type) {
            case "paths" -> {
                List<List<MethodId>> paths = q.paths(ModelInputs.select(model, a, "from"),
                        ModelInputs.select(model, a, "to"), maxDepth, limit);
                if (paths.isEmpty()) {
                    out.println("No path within " + maxDepth + " calls.");
                }
                for (int i = 0; i < paths.size(); i++) {
                    out.printf("path %d (%d calls):%n", i + 1, paths.get(i).size() - 1);
                    printPath(out, model.graph(), paths.get(i));
                }
            }
            case "guard" -> {
                Set<MethodId> from = ModelInputs.select(model, a, "from");
                Set<MethodId> to = ModelInputs.select(model, a, "to");
                Set<MethodId> through = ModelInputs.select(model, a, "through");
                Optional<List<MethodId>> bad = q.pathAvoiding(from, to, through);
                if (bad.isEmpty()) {
                    out.println("OK: every path from " + a.get("from", null) + " to " + a.get("to", null)
                            + " passes through " + a.get("through", null));
                    return 0;
                }
                out.println("VIOLATION: path from " + a.get("from", null) + " to " + a.get("to", null)
                        + " that does not pass through " + a.get("through", null) + ":");
                printPath(out, model.graph(), bad.get());
                return VIOLATION;
            }
            default -> {
                Set<MethodId> start = ModelInputs.select(model, a, "method");
                Map<MethodId, Integer> found = type.equals("callers") ? q.callers(start, maxDepth)
                        : q.callees(start, maxDepth);
                List<Map.Entry<MethodId, Integer>> rows = new ArrayList<>(found.entrySet());
                rows.removeIf(e -> e.getValue() == 0);
                rows.sort(Map.Entry.<MethodId, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey()));
                rows.stream().limit(limit).forEach(e -> out.printf("%3d  %s%n", e.getValue(), e.getKey().javaName()));
                if (rows.size() > limit) {
                    out.printf("... %d more (use --limit)%n", rows.size() - limit);
                }
            }
        }
        return 0;
    }

    private static void printPath(PrintStream out, CallGraph graph, List<MethodId> path) {
        out.println("     " + path.get(0).javaName());
        for (int i = 1; i < path.size(); i++) {
            Edge e = graph.edge(path.get(i - 1), path.get(i));
            out.println("  -> " + path.get(i).javaName() + "   [" + describe(e) + "]");
        }
    }

    private static String describe(Edge e) {
        StringBuilder sb = new StringBuilder(switch (e.provenance()) {
            case STATIC -> "static";
            case DYNAMIC -> "runtime";
            case BOTH -> "static+runtime";
        });
        if (!e.lines().isEmpty()) {
            sb.append(", line ").append(e.lines().stream().map(String::valueOf).collect(Collectors.joining(",")));
        }
        if (e.runtimeCount() > 0) {
            sb.append(", x").append(e.runtimeCount());
        }
        return sb.toString();
    }
}
