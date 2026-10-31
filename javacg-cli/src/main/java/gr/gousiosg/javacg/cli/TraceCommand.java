package gr.gousiosg.javacg.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import gr.gousiosg.javacg.core.io.CallGraphDot;
import gr.gousiosg.javacg.core.io.CallGraphJson;
import gr.gousiosg.javacg.core.trace.Recording;
import gr.gousiosg.javacg.core.trace.TraceSummary;
import gr.gousiosg.javacg.core.trace.TraceText;

/** {@code javacg trace}: read a recording written by the agent. */
final class TraceCommand implements Command {

    enum Format {
        TEXT, PAIRS, TIMING, JSON, DOT
    }

    @Override
    public String name() {
        return "trace";
    }

    @Override
    public String summary() {
        return "Print a recording made by javacg-agent.jar (trace, call pairs, timing, graph)";
    }

    @Override
    public String usage() {
        return """
                Usage: javacg trace [options] <recording-dir>

                  --format <f>     text    one line per entry/exit, the original calltrace.txt format (default)
                                   pairs   caller callee count, the original shutdown summary
                                   timing  self/total time, calls and recursion depth per method
                                   json    runtime call graph
                                   dot     runtime call graph for Graphviz
                  --output <file>  write to a file instead of stdout
                  --wallclock      text format: epoch milliseconds instead of nanoTime, to line up
                                   recordings of several JVMs
                """;
    }

    @Override
    public int run(List<String> argv, PrintStream out, PrintStream err) throws Exception {
        Args a = Args.parse(argv, Set.of("wallclock"), Set.of("format", "output"));
        if (a.positional().size() != 1) {
            throw new Args.UsageException("Expected one recording directory");
        }
        Format format = a.choice("format", Format.class, Format.TEXT);
        Recording rec = Recording.open(Path.of(a.positional().get(0)));
        if (!rec.isComplete()) {
            err.println("warning: recording was not stopped cleanly; symbol tables may be incomplete");
        }
        TraceSummary summary = format == Format.TEXT ? null : TraceSummary.of(rec);
        Output.write(a.get("output", null), out, w -> {
            try {
                switch (format) {
                    case TEXT -> TraceText.write(rec, w, a.flag("wallclock"));
                    case PAIRS -> summary.writePairs(w);
                    case TIMING -> summary.writeTiming(w);
                    case JSON -> CallGraphJson.write(summary.graph(), w);
                    case DOT -> CallGraphDot.write(summary.graph(), w, CallGraphDot.Level.METHOD);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        return 0;
    }
}
