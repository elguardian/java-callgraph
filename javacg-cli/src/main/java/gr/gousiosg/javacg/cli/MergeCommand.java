package gr.gousiosg.javacg.cli;

import java.io.PrintStream;
import java.util.List;

import gr.gousiosg.javacg.analyze.GapReport;
import gr.gousiosg.javacg.analyze.Model;
import gr.gousiosg.javacg.core.io.CallGraphDot;
import gr.gousiosg.javacg.core.io.CallGraphJson;

/** {@code javacg merge}: combine static and runtime call graphs. */
final class MergeCommand implements Command {

    enum Format {
        GAPS, JSON, DOT, DOT_CLASSES
    }

    @Override
    public String name() {
        return "merge";
    }

    @Override
    public String summary() {
        return "Merge static and runtime call graphs; report where they disagree";
    }

    @Override
    public String usage() {
        return """
                Usage: javacg merge [options]

                """ + ModelInputs.USAGE + """
                  --format <f>         gaps (default): runtime-only edges, unexercised static edges,
                                       instrumented methods that never ran
                                       json, dot, dot-classes: the merged graph
                  --limit <n>          entries per gaps section (default 50, -1 for all)
                  --output <file>      write to a file instead of stdout
                """;
    }

    @Override
    public int run(List<String> argv, PrintStream out, PrintStream err) throws Exception {
        Args a = Args.parse(argv, ModelInputs.flags(), ModelInputs.valued("format", "limit", "output"));
        Format format = a.choice("format", Format.class, Format.GAPS);
        int limit = ModelInputs.intOption(a, "limit", 50);
        Model model = ModelInputs.load(a, err);
        Output.write(a.get("output", null), out, w -> {
            switch (format) {
                case GAPS -> GapReport.of(model).write(w, limit);
                case JSON -> CallGraphJson.write(model.graph(), w);
                case DOT -> CallGraphDot.write(model.graph(), w, CallGraphDot.Level.METHOD);
                case DOT_CLASSES -> CallGraphDot.write(model.graph(), w, CallGraphDot.Level.CLASS);
            }
        });
        return 0;
    }
}
