package io.github.elguardian.mcpsettools.cli;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import io.github.elguardian.mcpsettools.core.io.CallGraphDot;
import io.github.elguardian.mcpsettools.core.io.CallGraphJson;
import io.github.elguardian.mcpsettools.stat.Algorithm;
import io.github.elguardian.mcpsettools.stat.LegacyTextWriter;
import io.github.elguardian.mcpsettools.stat.StaticAnalyzer;
import io.github.elguardian.mcpsettools.stat.StaticResult;

/** {@code mcp-set-tools static}: static call graph of application classes. */
final class StaticCommand implements Command {

    enum Format {
        TEXT, JSON, DOT, DOT_CLASSES
    }

    @Override
    public String name() {
        return "static";
    }

    @Override
    public String summary() {
        return "Build the static call graph of application jars/classes/WARs";
    }

    @Override
    public String usage() {
        return """
                Usage: mcp-set-tools static [options] [<app-input>...]

                Inputs are jars, class directories, .class files or WAR/EAR archives (nested
                jars are read). Positional arguments are application inputs.

                  --app <path>         application input (repeatable, or path-separator list)
                  --lib <path>         library input: resolution only, bodies not analyzed
                  --algorithm cha|rta  virtual call resolution (default cha)
                  --format <f>         text (original M:/C: format, default), json, dot, dot-classes
                  --output <file>      write to a file instead of stdout
                  --no-jdk             do not load missing classes from the running JDK
                """;
    }

    @Override
    public int run(List<String> argv, PrintStream out, PrintStream err) throws Exception {
        Args a = Args.parse(argv, Set.of("no-jdk"), Set.of("app", "lib", "algorithm", "format", "output"));
        List<Path> app = new ArrayList<>(a.paths("app"));
        a.positional().forEach(p -> app.add(Path.of(p)));
        if (app.isEmpty()) {
            throw new Args.UsageException("No application inputs");
        }
        Format format = a.choice("format", Format.class, Format.TEXT);
        StaticResult r = StaticAnalyzer.create().app(app).lib(a.paths("lib"))
                .algorithm(a.choice("algorithm", Algorithm.class, Algorithm.CHA)).jdkFallback(!a.flag("no-jdk"))
                .run();
        r.warnings().forEach(w -> err.println("warning: " + w));
        Output.write(a.get("output", null), out, w -> {
            switch (format) {
                case TEXT -> LegacyTextWriter.write(r, w);
                case JSON -> CallGraphJson.write(r.graph(), w);
                case DOT -> CallGraphDot.write(r.graph(), w, CallGraphDot.Level.METHOD);
                case DOT_CLASSES -> CallGraphDot.write(r.graph(), w, CallGraphDot.Level.CLASS);
            }
        });
        return 0;
    }
}
