package io.github.elguardian.mcpsettools.callgraph.tool;

import io.github.elguardian.mcpsettools.tool.ToolArgs;
import io.github.elguardian.mcpsettools.tool.ToolException;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.elguardian.mcpsettools.analyze.MethodPattern;
import io.github.elguardian.mcpsettools.analyze.Model;
import io.github.elguardian.mcpsettools.core.MethodId;
import io.github.elguardian.mcpsettools.core.trace.TraceSummary;
import io.github.elguardian.mcpsettools.stat.Algorithm;
import io.github.elguardian.mcpsettools.stat.StaticAnalyzer;
import io.github.elguardian.mcpsettools.stat.StaticResult;

/**
 * The models loaded in this server. Building a model (static analysis of a big deployment,
 * replaying recordings) is the expensive step, so it is done once and queried many times.
 */
final class Models {

    /** What a model was built from; loading the same inputs again replaces the model under the same id. */
    record Inputs(List<Path> app, List<Path> lib, List<Path> trace, Algorithm algorithm, boolean jdk) {
    }

    record Loaded(String id, Inputs inputs, Model model, List<String> warnings, Duration took) {
    }

    private final Map<String, Loaded> byId = new ConcurrentHashMap<>();
    private final AtomicInteger next = new AtomicInteger(1);

    Loaded load(Inputs in) throws IOException {
        if (in.app().isEmpty() && in.trace().isEmpty()) {
            throw new ToolException("Give 'app' inputs, 'trace' recordings, or both");
        }
        long start = System.nanoTime();
        List<String> warnings = new ArrayList<>();
        StaticResult statics = null;
        if (!in.app().isEmpty()) {
            statics = StaticAnalyzer.create().app(in.app()).lib(in.lib()).algorithm(in.algorithm())
                    .jdkFallback(in.jdk()).run();
            warnings.addAll(statics.warnings());
        }
        List<TraceSummary> traces = Model.loadRecordings(in.trace());
        for (TraceSummary t : traces) {
            if (!t.recording().isComplete()) {
                warnings.add("recording " + t.recording().directory() + " was not stopped cleanly; "
                        + "its tables may be incomplete");
            }
        }
        Model model = Model.of(statics, traces);
        String id = byId.values().stream().filter(l -> l.inputs().equals(in)).map(Loaded::id).findFirst()
                .orElseGet(() -> "m" + next.getAndIncrement());
        Loaded loaded = new Loaded(id, in, model, List.copyOf(warnings), Duration.ofNanos(System.nanoTime() - start));
        byId.put(id, loaded);
        return loaded;
    }

    /** The model named by the {@code model_id} argument, or the only loaded model if it is omitted. */
    Loaded get(ToolArgs args) {
        String id = args.string("model_id", null);
        if (id == null) {
            if (byId.size() == 1) {
                return byId.values().iterator().next();
            }
            throw new ToolException(byId.isEmpty() ? "No model loaded: call load_model first"
                    : "Several models are loaded " + byId.keySet() + ": pass model_id");
        }
        Loaded l = byId.get(id);
        if (l == null) {
            throw new ToolException("Unknown model_id " + id + (byId.isEmpty() ? ": call load_model first"
                    : "; loaded: " + byId.keySet()));
        }
        return l;
    }

    Collection<Loaded> all() {
        return byId.values().stream().sorted((a, b) -> Integer.compare(num(a.id()), num(b.id()))).toList();
    }

    boolean remove(String id) {
        return byId.remove(id) != null;
    }

    /** Methods of the model matching the pattern in argument {@code name}; an error if none match. */
    static Set<MethodId> select(Model model, ToolArgs args, String name) {
        String pattern = args.require(name);
        Set<MethodId> selected = resolve(model, pattern);
        if (selected.isEmpty()) {
            throw noMatch(name, pattern);
        }
        return selected;
    }

    /** Methods matching a pattern, retried as {@code *.<pattern>} when a pattern without a leading * matches nothing. */
    static Set<MethodId> resolve(Model model, String pattern) {
        Set<MethodId> all = candidates(model);
        Set<MethodId> found = pattern(pattern).select(all);
        if (found.isEmpty() && !pattern.startsWith("*")) {
            found = pattern("*." + pattern).select(all);
        }
        return found;
    }

    static ToolException noMatch(String argument, String pattern) {
        return new ToolException("'" + argument + "' pattern " + pattern + " matches no method of the model "
                + "(use find_methods to look names up)");
    }

    static MethodPattern pattern(String pattern) {
        try {
            return MethodPattern.parse(pattern);
        } catch (IllegalArgumentException e) {
            throw new ToolException(e.getMessage());
        }
    }

    /** Every method the model knows: graph nodes and instrumented methods that never ran. */
    static Set<MethodId> candidates(Model model) {
        Set<MethodId> all = new HashSet<>(model.graph().methods());
        all.addAll(model.instrumented());
        return all;
    }

    private static int num(String id) {
        try {
            return Integer.parseInt(id.substring(1));
        } catch (RuntimeException e) {
            return Integer.MAX_VALUE;
        }
    }
}
