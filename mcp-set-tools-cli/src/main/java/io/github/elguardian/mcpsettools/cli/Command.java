package io.github.elguardian.mcpsettools.cli;

import java.io.PrintStream;
import java.util.List;

/** A {@code mcp-set-tools} sub-command. */
interface Command {

    String name();

    /** One-line description for the command list. */
    String summary();

    /** Full usage text. */
    String usage();

    /** Returns the process exit code. */
    int run(List<String> argv, PrintStream out, PrintStream err) throws Exception;
}
