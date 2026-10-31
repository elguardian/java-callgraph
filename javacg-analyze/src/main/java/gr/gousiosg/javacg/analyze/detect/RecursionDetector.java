package gr.gousiosg.javacg.analyze.detect;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import gr.gousiosg.javacg.analyze.Model;
import gr.gousiosg.javacg.core.Edge;
import gr.gousiosg.javacg.core.MethodId;
import gr.gousiosg.javacg.core.trace.TraceSummary;
import gr.gousiosg.javacg.stat.MethodInfo;

/**
 * Recursion: deep recursion observed at runtime (stack overflow risk on bigger inputs), and
 * mutual recursion cycles in the static call graph (for review; often intended).
 */
final class RecursionDetector implements Detector {

    static final String DEEP = "recursion.deep";
    static final String CYCLE = "recursion.cycle";

    /** Frames of one method on a single stack from which recursion counts as deep. */
    static final int DEEP_THRESHOLD = 200;

    @Override
    public String id() {
        return "recursion";
    }

    @Override
    public Map<String, String> rules() {
        return Map.of(DEEP, "A method was observed recursing at least " + DEEP_THRESHOLD + " levels deep",
                CYCLE, "Methods calling each other in a cycle (mutual recursion)");
    }

    @Override
    public List<Finding> run(Model model) {
        List<Finding> findings = new ArrayList<>();
        for (Map.Entry<MethodId, TraceSummary.MethodStats> e : model.runtime().entrySet()) {
            int depth = e.getValue().maxRecursion();
            if (depth >= DEEP_THRESHOLD) {
                findings.add(new Finding(DEEP, Finding.Severity.MEDIUM, Finding.Confidence.HIGH,
                        "Recursion " + depth + " levels deep; larger inputs may overflow the stack", e.getKey(),
                        firstLine(model, e.getKey()), List.of(),
                        List.of("max " + depth + " simultaneous frames on one thread, " + e.getValue().calls()
                                + " calls")));
            }
        }
        if (model.hasStatic()) {
            Set<MethodId> app = model.statics().appClasses().stream().flatMap(c -> c.methods().stream())
                    .map(MethodInfo::id).collect(Collectors.toSet());
            for (List<MethodId> cycle : Cycles.find(app.stream().sorted().toList(),
                    m -> model.graph().outgoing(m).stream().map(Edge::callee).filter(app::contains).toList())) {
                List<Finding.Location> related = cycle.stream().skip(1)
                        .map(m -> new Finding.Location(m, firstLine(model, m), "in the cycle")).toList();
                findings.add(new Finding(CYCLE, Finding.Severity.INFO, Finding.Confidence.HIGH,
                        "Mutual recursion: " + cycle.stream().map(MethodId::javaName).collect(Collectors.joining(" -> "))
                                + " -> back; check that it terminates",
                        cycle.get(0), firstLine(model, cycle.get(0)), related, List.of()));
            }
        }
        return findings;
    }

    static int firstLine(Model model, MethodId m) {
        if (model.hasStatic()) {
            MethodInfo info = model.statics().appMethod(m);
            if (info != null && info.hasCode()) {
                return Bytecode.firstLine(info.node());
            }
        }
        for (TraceSummary t : model.traces()) {
            Integer id = t.recording().methodId(m);
            if (id != null && t.recording().firstLine(id) > 0) {
                return t.recording().firstLine(id);
            }
        }
        return -1;
    }
}
