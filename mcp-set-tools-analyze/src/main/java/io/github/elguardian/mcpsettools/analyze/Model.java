package io.github.elguardian.mcpsettools.analyze;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import io.github.elguardian.mcpsettools.core.CallGraph;
import io.github.elguardian.mcpsettools.core.MethodId;
import io.github.elguardian.mcpsettools.core.trace.Recording;
import io.github.elguardian.mcpsettools.core.trace.TraceSummary;
import io.github.elguardian.mcpsettools.rt.TraceFormat;
import io.github.elguardian.mcpsettools.stat.StaticResult;

/**
 * Everything known about a program: the static analysis (optional), any number of runtime
 * recordings (optional), and the merged call graph whose edges carry their provenance.
 */
public final class Model {

    private final StaticResult statics;
    private final List<TraceSummary> traces;
    private final CallGraph graph = new CallGraph();
    private final Set<MethodId> instrumented = new HashSet<>();
    private final Set<MethodId> executed = new HashSet<>();
    private final Map<MethodId, TraceSummary.MethodStats> runtimeStats = new HashMap<>();

    private Model(StaticResult statics, List<TraceSummary> traces) {
        this.statics = statics;
        this.traces = List.copyOf(traces);
        if (statics != null) {
            graph.merge(statics.graph());
        }
        for (TraceSummary t : traces) {
            graph.merge(t.graph());
            instrumented.addAll(t.recording().instrumentedMethods());
            executed.addAll(t.stats().keySet());
        }
        Map<MethodId, List<TraceSummary.MethodStats>> byMethod = new HashMap<>();
        traces.forEach(t -> t.stats().forEach((m, st) -> byMethod.computeIfAbsent(m, k -> new ArrayList<>()).add(st)));
        byMethod.forEach((m, list) -> runtimeStats.put(m, TraceSummary.MethodStats.sum(list)));
    }

    public static Model of(StaticResult statics, List<TraceSummary> traces) {
        if (statics == null && traces.isEmpty()) {
            throw new IllegalArgumentException("Need a static analysis, recordings, or both");
        }
        return new Model(statics, traces);
    }

    /**
     * Loads recordings: each path is either a recording directory or a directory whose
     * immediate subdirectories are recordings (the agent's {@code out=} directory).
     */
    public static List<TraceSummary> loadRecordings(List<Path> paths) throws IOException {
        List<TraceSummary> result = new ArrayList<>();
        for (Path p : paths) {
            for (Path dir : recordingDirectories(p)) {
                result.add(TraceSummary.of(Recording.open(dir)));
            }
        }
        return result;
    }

    static List<Path> recordingDirectories(Path p) throws IOException {
        if (Files.isRegularFile(p.resolve(TraceFormat.EVENTS))) {
            return List.of(p);
        }
        if (!Files.isDirectory(p)) {
            throw new java.nio.file.NoSuchFileException(p.toString(), null, "not a recording directory");
        }
        List<Path> dirs;
        try (Stream<Path> children = Files.list(p)) {
            dirs = children.filter(c -> Files.isRegularFile(c.resolve(TraceFormat.EVENTS))).sorted().toList();
        }
        if (dirs.isEmpty()) {
            throw new java.nio.file.NoSuchFileException(p.toString(), null, "no recordings found");
        }
        return dirs;
    }

    /** The static analysis, or null if only recordings were given. */
    public StaticResult statics() {
        return statics;
    }

    public List<TraceSummary> traces() {
        return traces;
    }

    /** Static and runtime edges merged; see {@link io.github.elguardian.mcpsettools.core.Edge#provenance()}. */
    public CallGraph graph() {
        return graph;
    }

    /** Methods the agent instrumented in any recording. */
    public Set<MethodId> instrumented() {
        return Collections.unmodifiableSet(instrumented);
    }

    /** Methods that ran in any recording. */
    public Set<MethodId> executed() {
        return Collections.unmodifiableSet(executed);
    }

    /** Runtime statistics of a method summed over all recordings, or null if it never ran. */
    public TraceSummary.MethodStats runtime(MethodId m) {
        return runtimeStats.get(m);
    }

    /** Runtime statistics of every method that ran. */
    public Map<MethodId, TraceSummary.MethodStats> runtime() {
        return Collections.unmodifiableMap(runtimeStats);
    }

    public boolean hasStatic() {
        return statics != null;
    }

    public boolean hasRuntime() {
        return !traces.isEmpty();
    }
}
