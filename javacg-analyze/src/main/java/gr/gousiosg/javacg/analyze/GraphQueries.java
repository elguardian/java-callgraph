package gr.gousiosg.javacg.analyze;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import gr.gousiosg.javacg.core.CallGraph;
import gr.gousiosg.javacg.core.Edge;
import gr.gousiosg.javacg.core.MethodId;
import gr.gousiosg.javacg.core.Provenance;

/** Reachability queries over a call graph, optionally restricted to edges of one provenance. */
public final class GraphQueries {

    /** Which edges a query may follow. */
    public enum Edges {
        ALL(e -> true),
        /** Edges found by the static analysis (static only or both). */
        STATIC(e -> e.provenance() != Provenance.DYNAMIC),
        /** Edges observed at runtime (runtime only or both). */
        DYNAMIC(e -> e.provenance() != Provenance.STATIC);

        private final Predicate<Edge> filter;

        Edges(Predicate<Edge> filter) {
            this.filter = filter;
        }
    }

    private final CallGraph graph;
    private final Predicate<Edge> follow;

    public GraphQueries(CallGraph graph, Edges edges) {
        this.graph = graph;
        this.follow = edges.filter;
    }

    /**
     * Shortest call paths from any of {@code from} to any of {@code to}, at most {@code limit}
     * of them and at most {@code maxEdges} edges long. Each path starts with a {@code from}
     * method and ends with a {@code to} method.
     */
    public List<List<MethodId>> paths(Collection<MethodId> from, Collection<MethodId> to, int maxEdges, int limit) {
        Set<MethodId> targets = Set.copyOf(to);
        // BFS from the sources, remembering every shortest-path predecessor
        Map<MethodId, Integer> dist = new HashMap<>();
        Map<MethodId, List<MethodId>> preds = new HashMap<>();
        Deque<MethodId> work = new ArrayDeque<>();
        for (MethodId s : from) {
            if (dist.putIfAbsent(s, 0) == null) {
                work.add(s);
            }
        }
        List<MethodId> reached = new ArrayList<>();
        int foundAt = Integer.MAX_VALUE;
        while (!work.isEmpty()) {
            MethodId m = work.poll();
            int d = dist.get(m);
            if (targets.contains(m) && d <= foundAt) {
                foundAt = d;
                reached.add(m);
            }
            if (d >= maxEdges || d >= foundAt) {
                continue;
            }
            for (Edge e : graph.outgoing(m)) {
                if (!follow.test(e)) {
                    continue;
                }
                MethodId n = e.callee();
                Integer nd = dist.get(n);
                if (nd == null) {
                    dist.put(n, d + 1);
                    preds.computeIfAbsent(n, k -> new ArrayList<>()).add(m);
                    work.add(n);
                } else if (nd == d + 1) {
                    preds.computeIfAbsent(n, k -> new ArrayList<>()).add(m);
                }
            }
        }
        List<List<MethodId>> result = new ArrayList<>();
        for (MethodId t : reached) {
            unfold(t, preds, dist, new ArrayDeque<>(List.of(t)), result, limit);
            if (result.size() >= limit) {
                break;
            }
        }
        return result;
    }

    private static void unfold(MethodId m, Map<MethodId, List<MethodId>> preds, Map<MethodId, Integer> dist,
            Deque<MethodId> suffix, List<List<MethodId>> out, int limit) {
        if (out.size() >= limit) {
            return;
        }
        if (dist.get(m) == 0) {
            out.add(List.copyOf(suffix));
            return;
        }
        for (MethodId p : preds.getOrDefault(m, List.of())) {
            suffix.addFirst(p);
            unfold(p, preds, dist, suffix, out, limit);
            suffix.removeFirst();
        }
    }

    /**
     * Checks that every call path from {@code from} to {@code to} goes through one of
     * {@code through} (a "guard"). Returns a counterexample path that avoids all of them, or
     * empty if the property holds. Sources and targets that are themselves guards count as
     * guarded.
     */
    public Optional<List<MethodId>> pathAvoiding(Collection<MethodId> from, Collection<MethodId> to,
            Collection<MethodId> through) {
        Set<MethodId> guards = Set.copyOf(through);
        Set<MethodId> targets = Set.copyOf(to);
        Map<MethodId, MethodId> parent = new HashMap<>();
        Deque<MethodId> work = new ArrayDeque<>();
        for (MethodId s : from) {
            if (!guards.contains(s) && parent.putIfAbsent(s, s) == null) {
                work.add(s);
            }
        }
        while (!work.isEmpty()) {
            MethodId m = work.poll();
            if (targets.contains(m)) {
                List<MethodId> path = new ArrayList<>();
                for (MethodId k = m; ; k = parent.get(k)) {
                    path.add(0, k);
                    if (parent.get(k).equals(k)) {
                        break;
                    }
                }
                return Optional.of(path);
            }
            for (Edge e : graph.outgoing(m)) {
                MethodId n = e.callee();
                if (follow.test(e) && !guards.contains(n) && parent.putIfAbsent(n, m) == null) {
                    work.add(n);
                }
            }
        }
        return Optional.empty();
    }

    /** Methods reachable from {@code from} within {@code maxEdges} calls, with their distance. */
    public Map<MethodId, Integer> callees(Collection<MethodId> from, int maxEdges) {
        return walk(from, maxEdges, true);
    }

    /** Methods that reach {@code to} within {@code maxEdges} calls, with their distance. */
    public Map<MethodId, Integer> callers(Collection<MethodId> to, int maxEdges) {
        return walk(to, maxEdges, false);
    }

    private Map<MethodId, Integer> walk(Collection<MethodId> start, int maxEdges, boolean forward) {
        Map<MethodId, Integer> dist = new LinkedHashMap<>();
        Deque<MethodId> work = new ArrayDeque<>();
        for (MethodId s : start) {
            if (dist.putIfAbsent(s, 0) == null) {
                work.add(s);
            }
        }
        while (!work.isEmpty()) {
            MethodId m = work.poll();
            int d = dist.get(m);
            if (d >= maxEdges) {
                continue;
            }
            Collection<Edge> edges = forward ? graph.outgoing(m) : graph.incoming(m);
            for (Edge e : edges) {
                MethodId n = forward ? e.callee() : e.caller();
                if (follow.test(e) && dist.putIfAbsent(n, d + 1) == null) {
                    work.add(n);
                }
            }
        }
        return dist;
    }
}
