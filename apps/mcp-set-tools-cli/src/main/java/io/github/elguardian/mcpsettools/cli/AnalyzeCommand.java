package io.github.elguardian.mcpsettools.cli;

import java.io.PrintStream;
import java.util.List;
import java.util.Map;

import io.github.elguardian.mcpsettools.analyze.Model;
import io.github.elguardian.mcpsettools.analyze.detect.Detector;
import io.github.elguardian.mcpsettools.analyze.detect.Detectors;
import io.github.elguardian.mcpsettools.analyze.detect.Finding;
import io.github.elguardian.mcpsettools.analyze.report.SarifWriter;

/** {@code mcp-set-tools analyze}: run the bug detectors. */
final class AnalyzeCommand implements Command {

    enum Format {
        TEXT, SARIF
    }

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
                Usage: mcp-set-tools analyze [options]

                """ + ModelInputs.USAGE + """
                  --detector <id>      run only these detectors (repeatable, default all)
                  --min-severity <s>   high, medium, low or info (default info)
                  --fail-on <s>        exit 4 if a finding at or above this severity exists
                  --format text|sarif  report format (default text)
                  --output <file>      write the report to a file instead of stdout
                  --sarif <file>       also write a SARIF 2.1.0 report to this file
                  --source-root <dir>  prefix for SARIF source paths, e.g. src/main/java

                Detectors and rules:
                """ + rules;
    }

    @Override
    public int run(List<String> argv, PrintStream out, PrintStream err) throws Exception {
        Args a = Args.parse(argv, ModelInputs.flags(),
                ModelInputs.valued("detector", "min-severity", "fail-on", "format",
                        "output", "sarif", "source-root"));
        List<Detector> detectors;
        try {
            detectors = Detectors.select(a.all("detector"));
        } catch (IllegalArgumentException e) {
            throw new Args.UsageException(e.getMessage());
        }
        Finding.Severity min = a.choice("min-severity", Finding.Severity.class, Finding.Severity.INFO);
        Finding.Severity failOn = a.choice("fail-on", Finding.Severity.class, null);
        Format format = a.choice("format", Format.class, Format.TEXT);
        Model model = ModelInputs.load(a, err);
        List<Finding> findings = Detectors.atLeast(Detectors.run(model, detectors), min);
        SarifWriter sarif = new SarifWriter(model, a.get("source-root", ""));
        Map<String, String> rules = Detectors.rules(detectors);
        Output.write(a.get("output", null), out, w -> {
            if (format == Format.SARIF) {
                sarif.write(findings, rules, w);
            } else {
                Detectors.writeText(findings, w);
            }
        });
        String sarifFile = a.get("sarif", null);
        if (sarifFile != null) {
            Output.write(sarifFile, out, w -> sarif.write(findings, rules, w));
        }
        if (failOn != null && !Detectors.atLeast(findings, failOn).isEmpty()) {
            return FINDINGS;
        }
        return 0;
    }
}
