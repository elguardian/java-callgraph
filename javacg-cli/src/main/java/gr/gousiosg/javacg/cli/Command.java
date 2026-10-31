package gr.gousiosg.javacg.cli;

import java.io.PrintStream;
import java.util.List;

/** A {@code javacg} sub-command. */
interface Command {

    String name();

    /** One-line description for the command list. */
    String summary();

    /** Full usage text. */
    String usage();

    /** Returns the process exit code. */
    int run(List<String> argv, PrintStream out, PrintStream err) throws Exception;
}
