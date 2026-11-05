package io.github.elguardian.mcpsettools.stat;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.elguardian.mcpsettools.core.CallGraph;
import io.github.elguardian.mcpsettools.core.MethodId;

/** Output of {@link StaticAnalyzer#run()}. */
public final class StaticResult {

    private final ClassHierarchy hierarchy;
    private final CallGraph graph;
    private final List<CallSite> callSites;
    private final List<ClassInfo> appClasses;
    private final List<String> warnings;
    private final Algorithm algorithm;
    private final Map<MethodId, MethodInfo> appMethods = new HashMap<>();

    StaticResult(ClassHierarchy hierarchy, CallGraph graph, List<CallSite> callSites, List<ClassInfo> appClasses,
            List<String> warnings, Algorithm algorithm) {
        this.hierarchy = hierarchy;
        this.graph = graph;
        this.callSites = Collections.unmodifiableList(callSites);
        this.appClasses = Collections.unmodifiableList(appClasses);
        this.warnings = Collections.unmodifiableList(warnings);
        this.algorithm = algorithm;
        appClasses.forEach(c -> c.methods().forEach(m -> appMethods.put(m.id(), m)));
    }

    public ClassHierarchy hierarchy() {
        return hierarchy;
    }

    /** Call graph: every application method is a node; edges have {@code STATIC} provenance. */
    public CallGraph graph() {
        return graph;
    }

    /** Every call instruction in application code, in class/method/instruction order. */
    public List<CallSite> callSites() {
        return callSites;
    }

    /** Application classes sorted by name. */
    public List<ClassInfo> appClasses() {
        return appClasses;
    }

    /** Application method by id, or null if the id is not an application method. */
    public MethodInfo appMethod(MethodId id) {
        return appMethods.get(id);
    }

    public List<String> warnings() {
        return warnings;
    }

    public Algorithm algorithm() {
        return algorithm;
    }
}
