package gr.gousiosg.javacg.analyze;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import gr.gousiosg.javacg.core.CallGraph;
import gr.gousiosg.javacg.core.MethodId;
import gr.gousiosg.javacg.core.trace.Recording;
import gr.gousiosg.javacg.core.trace.TraceSummary;
import gr.gousiosg.javacg.rt.TraceFormat;
import gr.gousiosg.javacg.stat.StaticResult;

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

    /** Static and runtime edges merged; see {@link gr.gousiosg.javacg.core.Edge#provenance()}. */
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

    public boolean hasStatic() {
        return statics != null;
    }

    public boolean hasRuntime() {
        return !traces.isEmpty();
    }
}
