package gr.gousiosg.javacg.core;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * A caller → callee edge. One edge aggregates every call site between the two methods (static
 * kinds and line numbers) and every runtime observation (invocation count).
 */
public final class Edge {

    private final MethodId caller;
    private final MethodId callee;
    private final EnumSet<CallKind> kinds = EnumSet.noneOf(CallKind.class);
    private final SortedSet<Integer> lines = new TreeSet<>();
    private boolean seenStatically;
    private boolean seenAtRuntime;
    private long runtimeCount;

    Edge(MethodId caller, MethodId callee) {
        this.caller = caller;
        this.callee = callee;
    }

    public MethodId caller() {
        return caller;
    }

    public MethodId callee() {
        return callee;
    }

    public Set<CallKind> kinds() {
        return Collections.unmodifiableSet(kinds);
    }

    /** Source lines of the static call sites; empty when unknown. */
    public SortedSet<Integer> lines() {
        return Collections.unmodifiableSortedSet(lines);
    }

    /** Number of invocations observed at runtime; 0 for static-only edges. */
    public long runtimeCount() {
        return runtimeCount;
    }

    public Provenance provenance() {
        return Provenance.of(seenStatically, seenAtRuntime);
    }

    void addStatic(CallKind kind, int line) {
        seenStatically = true;
        kinds.add(kind);
        if (line > 0) {
            lines.add(line);
        }
    }

    void addRuntime(long count) {
        seenAtRuntime = true;
        runtimeCount += count;
    }

    void mergeFrom(Edge o) {
        seenStatically |= o.seenStatically;
        seenAtRuntime |= o.seenAtRuntime;
        kinds.addAll(o.kinds);
        lines.addAll(o.lines);
        runtimeCount += o.runtimeCount;
    }

    @Override
    public String toString() {
        return caller + " -> " + callee + " " + provenance();
    }
}
