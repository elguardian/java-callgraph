package gr.gousiosg.javacg.core;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Directed call graph over {@link MethodId}s. Static and runtime edges live in the same graph;
 * {@link Edge#provenance()} tells them apart. Not thread-safe.
 */
public final class CallGraph {

    private static final Comparator<Edge> EDGE_ORDER = Comparator.comparing(Edge::caller)
            .thenComparing(Edge::callee);

    private final Set<MethodId> methods = new HashSet<>();
    private final Map<MethodId, Map<MethodId, Edge>> out = new HashMap<>();
    private final Map<MethodId, Set<MethodId>> in = new HashMap<>();

    public void addMethod(MethodId m) {
        methods.add(m);
    }

    public Edge addStaticCall(MethodId caller, MethodId callee, CallKind kind, int line) {
        Edge e = edgeFor(caller, callee);
        e.addStatic(kind, line);
        return e;
    }

    public Edge addRuntimeCall(MethodId caller, MethodId callee, long count) {
        Edge e = edgeFor(caller, callee);
        e.addRuntime(count);
        return e;
    }

    /** Adds every method and edge of {@code other} into this graph. */
    public CallGraph merge(CallGraph other) {
        methods.addAll(other.methods);
        other.out.values().forEach(m -> m.values().forEach(e -> edgeFor(e.caller(), e.callee()).mergeFrom(e)));
        return this;
    }

    public Edge edge(MethodId caller, MethodId callee) {
        return out.getOrDefault(caller, Map.of()).get(callee);
    }

    public Collection<Edge> outgoing(MethodId m) {
        return Collections.unmodifiableCollection(out.getOrDefault(m, Map.of()).values());
    }

    public List<Edge> incoming(MethodId m) {
        return in.getOrDefault(m, Set.of()).stream().map(c -> out.get(c).get(m)).collect(Collectors.toList());
    }

    public Set<MethodId> callees(MethodId m) {
        return Collections.unmodifiableSet(out.getOrDefault(m, Map.of()).keySet());
    }

    public Set<MethodId> callers(MethodId m) {
        return Collections.unmodifiableSet(in.getOrDefault(m, Set.of()));
    }

    public Set<MethodId> methods() {
        return Collections.unmodifiableSet(methods);
    }

    public boolean contains(MethodId m) {
        return methods.contains(m);
    }

    public Stream<Edge> edges() {
        return out.values().stream().flatMap(m -> m.values().stream());
    }

    /** Edges sorted by caller then callee, for deterministic output. */
    public List<Edge> sortedEdges() {
        return edges().sorted(EDGE_ORDER).collect(Collectors.toList());
    }

    public int edgeCount() {
        return out.values().stream().mapToInt(Map::size).sum();
    }

    private Edge edgeFor(MethodId caller, MethodId callee) {
        methods.add(caller);
        methods.add(callee);
        in.computeIfAbsent(callee, k -> new HashSet<>()).add(caller);
        return out.computeIfAbsent(caller, k -> new LinkedHashMap<>()).computeIfAbsent(callee, k -> new Edge(caller, k));
    }
}
