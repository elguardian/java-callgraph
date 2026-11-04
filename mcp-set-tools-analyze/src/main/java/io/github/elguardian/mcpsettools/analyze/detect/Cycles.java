package io.github.elguardian.mcpsettools.analyze.detect;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Strongly connected components (iterative Tarjan) and one representative cycle per component. */
final class Cycles {

    private Cycles() {
    }

    /** Components with more than one node, in discovery order. */
    static <T> List<Set<T>> components(Collection<T> nodes, Function<T, ? extends Collection<T>> successors) {
        Map<T, Integer> index = new HashMap<>();
        Map<T, Integer> low = new HashMap<>();
        Deque<T> stack = new ArrayDeque<>();
        Set<T> onStack = new HashSet<>();
        List<Set<T>> result = new ArrayList<>();
        int[] counter = {0};
        for (T root : nodes) {
            if (index.containsKey(root)) {
                continue;
            }
            // explicit DFS stack of (node, successor iterator)
            Deque<Map.Entry<T, java.util.Iterator<T>>> dfs = new ArrayDeque<>();
            visit(root, index, low, stack, onStack, counter, dfs, successors);
            while (!dfs.isEmpty()) {
                Map.Entry<T, java.util.Iterator<T>> top = dfs.peek();
                T v = top.getKey();
                if (top.getValue().hasNext()) {
                    T w = top.getValue().next();
                    if (!index.containsKey(w)) {
                        visit(w, index, low, stack, onStack, counter, dfs, successors);
                    } else if (onStack.contains(w)) {
                        low.put(v, Math.min(low.get(v), index.get(w)));
                    }
                    continue;
                }
                dfs.pop();
                if (!dfs.isEmpty()) {
                    T parent = dfs.peek().getKey();
                    low.put(parent, Math.min(low.get(parent), low.get(v)));
                }
                if (low.get(v).equals(index.get(v))) {
                    Set<T> scc = new LinkedHashSet<>();
                    T w;
                    do {
                        w = stack.pop();
                        onStack.remove(w);
                        scc.add(w);
                    } while (!w.equals(v));
                    if (scc.size() > 1) {
                        result.add(scc);
                    }
                }
            }
        }
        return result;
    }

    private static <T> void visit(T v, Map<T, Integer> index, Map<T, Integer> low, Deque<T> stack, Set<T> onStack,
            int[] counter, Deque<Map.Entry<T, java.util.Iterator<T>>> dfs, Function<T, ? extends Collection<T>> succ) {
        index.put(v, counter[0]);
        low.put(v, counter[0]);
        counter[0]++;
        stack.push(v);
        onStack.add(v);
        dfs.push(Map.entry(v, new ArrayList<T>(succ.apply(v)).iterator()));
    }

    /** One simple cycle through each multi-node component (shortest from its first node). */
    static <T> List<List<T>> find(Collection<T> nodes, Function<T, ? extends Collection<T>> successors) {
        List<List<T>> cycles = new ArrayList<>();
        for (Set<T> scc : components(nodes, successors)) {
            T start = scc.iterator().next();
            Map<T, T> parent = new HashMap<>();
            Deque<T> work = new ArrayDeque<>(List.of(start));
            parent.put(start, start);
            T last = null;
            search:
            while (!work.isEmpty()) {
                T v = work.poll();
                for (T w : successors.apply(v)) {
                    if (!scc.contains(w)) {
                        continue;
                    }
                    if (w.equals(start)) {
                        last = v;
                        break search;
                    }
                    if (parent.putIfAbsent(w, v) == null) {
                        work.add(w);
                    }
                }
            }
            List<T> cycle = new ArrayList<>();
            for (T v = last; v != null && !v.equals(start); v = parent.get(v)) {
                cycle.add(0, v);
            }
            cycle.add(0, start);
            cycles.add(cycle);
        }
        return cycles;
    }
}
