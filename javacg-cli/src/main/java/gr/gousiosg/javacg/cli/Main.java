package gr.gousiosg.javacg.cli;

import java.io.PrintStream;
import java.nio.file.NoSuchFileException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Entry point of {@code java -jar javacg.jar <command> ...}. */
public final class Main {

    private static final List<Command> COMMANDS = List.of(new StaticCommand(), new TraceCommand(), new MergeCommand(),
            new QueryCommand(), new AnalyzeCommand());

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        Map<String, Command> commands = new LinkedHashMap<>();
        COMMANDS.forEach(c -> commands.put(c.name(), c));
        if (args.length == 0 || args[0].equals("--help") || args[0].equals("help") && args.length == 1) {
            usage(out, commands);
            return args.length == 0 ? 1 : 0;
        }
        if (args[0].equals("help")) {
            Command c = commands.get(args[1]);
            if (c == null) {
                err.println("Unknown command: " + args[1]);
                return 1;
            }
            out.print(c.usage());
            return 0;
        }
        Command c = commands.get(args[0]);
        if (c == null) {
            err.println("Unknown command: " + args[0]);
            usage(err, commands);
            return 1;
        }
        List<String> rest = Arrays.asList(args).subList(1, args.length);
        if (rest.contains("--help")) {
            out.print(c.usage());
            return 0;
        }
        try {
            return c.run(rest, out, err);
        } catch (Args.UsageException e) {
            err.println(e.getMessage());
            err.print(c.usage());
            return 1;
        } catch (NoSuchFileException e) {
            err.println("Input does not exist: " + e.getFile());
            return 1;
        } catch (Exception e) {
            err.println("Error: " + e);
            e.printStackTrace(err);
            return 2;
        }
    }

    private static void usage(PrintStream out, Map<String, Command> commands) {
        out.println("Usage: javacg <command> [options]   (javacg help <command> for details)");
        out.println();
        commands.values().forEach(c -> out.printf("  %-10s %s%n", c.name(), c.summary()));
    }
}
