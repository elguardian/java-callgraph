package gr.gousiosg.javacg.cli;

import java.io.PrintStream;
import java.util.List;

import gr.gousiosg.javacg.analyze.Model;
import gr.gousiosg.javacg.analyze.detect.Detector;
import gr.gousiosg.javacg.analyze.detect.Detectors;
import gr.gousiosg.javacg.analyze.detect.Finding;

/** {@code javacg analyze}: run the bug detectors. */
final class AnalyzeCommand implements Command {

    /** Exit code when {@code --fail-on} findings exist. */
    static final int FINDINGS = 4;

    @Override
    public String name() {
        return "analyze";
    }

    @Override
    public String summary() {
        return "Run the bug detectors over static analysis and/or recordings";
    }

    @Override
    public String usage() {
        StringBuilder rules = new StringBuilder();
        for (Detector d : Detectors.all()) {
            rules.append("  ").append(d.id()).append('\n');
            d.rules().forEach((id, text) -> rules.append(String.format("      %-32s %s%n", id, text)));
        }
        return """
                Usage: javacg analyze [options]

                """ + ModelInputs.USAGE + """
                  --detector <id>      run only these detectors (repeatable, default all)
                  --min-severity <s>   high, medium, low or info (default info)
                  --fail-on <s>        exit 4 if a finding at or above this severity exists
                  --output <file>      write to a file instead of stdout

                Detectors and rules:
                """ + rules;
    }

    @Override
    public int run(List<String> argv, PrintStream out, PrintStream err) throws Exception {
        Args a = Args.parse(argv, ModelInputs.flags(),
                ModelInputs.valued("detector", "min-severity", "fail-on", "output"));
        List<Detector> detectors;
        try {
            detectors = Detectors.select(a.all("detector"));
        } catch (IllegalArgumentException e) {
            throw new Args.UsageException(e.getMessage());
        }
        Finding.Severity min = a.choice("min-severity", Finding.Severity.class, Finding.Severity.INFO);
        Finding.Severity failOn = a.choice("fail-on", Finding.Severity.class, null);
        Model model = ModelInputs.load(a, err);
        List<Finding> findings = Detectors.atLeast(Detectors.run(model, detectors), min);
        Output.write(a.get("output", null), out, w -> Detectors.writeText(findings, w));
        if (failOn != null && !Detectors.atLeast(findings, failOn).isEmpty()) {
            return FINDINGS;
        }
        return 0;
    }
}
