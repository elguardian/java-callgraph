package gr.gousiosg.javacg.analyze.detect;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import gr.gousiosg.javacg.analyze.Model;
import gr.gousiosg.javacg.stat.ClassInfo;
import gr.gousiosg.javacg.stat.MethodInfo;

/**
 * Private methods nothing calls: no static call site, no method reference, and (when
 * recordings exist) never run. Reflection and serialization can still reach them, hence
 * medium confidence.
 */
final class DeadCodeDetector implements Detector {

    static final String UNUSED_PRIVATE = "dead.private-method";

    /** Called by serialization, never by name in code. */
    private static final Set<String> SERIALIZATION = Set.of("writeObject", "readObject", "readObjectNoData",
            "writeReplace", "readResolve");

    @Override
    public String id() {
        return "dead-code";
    }

    @Override
    public Map<String, String> rules() {
        return Map.of(UNUSED_PRIVATE, "A private method is never called");
    }

    @Override
    public List<Finding> run(Model model) {
        if (!model.hasStatic()) {
            return List.of();
        }
        List<Finding> findings = new ArrayList<>();
        for (ClassInfo c : model.statics().appClasses()) {
            for (MethodInfo m : c.methods()) {
                if (!m.isPrivate() || m.isSynthetic() || m.id().isConstructor() || m.id().isStaticInitializer()
                        || m.id().name().startsWith("lambda$") || SERIALIZATION.contains(m.id().name())) {
                    continue;
                }
                boolean called = model.graph().callers(m.id()).stream().anyMatch(caller -> !caller.equals(m.id()));
                if (!called && !model.executed().contains(m.id())) {
                    findings.add(new Finding(UNUSED_PRIVATE, Finding.Severity.LOW, Finding.Confidence.MEDIUM,
                            "Private method " + m.id().name() + " is never called", m.id(),
                            m.hasCode() ? Bytecode.firstLine(m.node()) : -1, List.of(), List.of()));
                }
            }
        }
        return findings;
    }
}
