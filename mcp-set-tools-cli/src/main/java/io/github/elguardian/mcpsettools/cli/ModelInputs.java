package io.github.elguardian.mcpsettools.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.elguardian.mcpsettools.analyze.MethodPattern;
import io.github.elguardian.mcpsettools.analyze.Model;
import io.github.elguardian.mcpsettools.core.MethodId;
import io.github.elguardian.mcpsettools.core.trace.TraceSummary;
import io.github.elguardian.mcpsettools.stat.Algorithm;
import io.github.elguardian.mcpsettools.stat.StaticAnalyzer;
import io.github.elguardian.mcpsettools.stat.StaticResult;

/** The static and runtime inputs shared by {@code merge}, {@code query} and {@code analyze}. */
final class ModelInputs {

    static final Set<String> FLAGS = Set.of("no-jdk");
    static final Set<String> VALUED = Set.of("app", "lib", "algorithm", "trace");

    static final String USAGE = """
              --app <path>         application input for the static analysis (repeatable)
              --lib <path>         library input: resolution only
              --algorithm cha|rta  virtual call resolution (default cha)
              --no-jdk             do not load missing classes from the running JDK
              --trace <dir>        a recording, or an agent out= directory holding several (repeatable)
            """;

    private ModelInputs() {
    }

    static Set<String> valued(String... more) {
        Set<String> s = new HashSet<>(VALUED);
        s.addAll(List.of(more));
        return s;
    }

    static Set<String> flags(String... more) {
        Set<String> s = new HashSet<>(FLAGS);
        s.addAll(List.of(more));
        return s;
    }

    static Model load(Args a, PrintStream err) throws IOException {
        List<Path> app = new ArrayList<>(a.paths("app"));
        List<Path> traces = a.paths("trace");
        if (app.isEmpty() && traces.isEmpty()) {
            throw new Args.UsageException("Give --app inputs, --trace recordings, or both");
        }
        StaticResult statics = null;
        if (!app.isEmpty()) {
            statics = StaticAnalyzer.create().app(app).lib(a.paths("lib"))
                    .algorithm(a.choice("algorithm", Algorithm.class, Algorithm.CHA)).jdkFallback(!a.flag("no-jdk"))
                    .run();
            statics.warnings().forEach(w -> err.println("warning: " + w));
        }
        List<TraceSummary> summaries = Model.loadRecordings(traces);
        for (TraceSummary s : summaries) {
            if (!s.recording().isComplete()) {
                err.println("warning: recording " + s.recording().directory() + " was not stopped cleanly");
            }
        }
        return Model.of(statics, summaries);
    }

    /** Methods of the model matching a pattern option; a usage error if none match. */
    static Set<MethodId> select(Model model, Args a, String option) {
        String pattern = a.require(option);
        Set<MethodId> all = new HashSet<>(model.graph().methods());
        all.addAll(model.instrumented());
        Set<MethodId> selected = MethodPattern.parse(pattern).select(all);
        if (selected.isEmpty()) {
            throw new Args.UsageException("--" + option + " " + pattern + " matches no method");
        }
        return selected;
    }

    static int intOption(Args a, String name, int dflt) {
        String v = a.get(name, null);
        if (v == null) {
            return dflt;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new Args.UsageException("--" + name + " must be a number, got " + v);
        }
    }
}
