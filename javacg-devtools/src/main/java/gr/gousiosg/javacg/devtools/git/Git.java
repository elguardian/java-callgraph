package gr.gousiosg.javacg.devtools.git;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** Runs {@code git -C <dir> ...} without a shell. */
public final class Git {

    /** Exit code and output of one command. */
    public record Result(int exit, String out, String err) {
        public boolean ok() {
            return exit == 0;
        }
    }

    private final Path dir;

    public Git(Path dir) {
        this.dir = dir.toAbsolutePath().normalize();
    }

    public Path dir() {
        return dir;
    }

    /** The top of the work tree containing {@code dir}, or empty if it is not in a git repository. */
    public static Optional<Path> top(Path dir) {
        try {
            Result r = new Git(dir).exec(Duration.ofSeconds(30), "rev-parse", "--show-toplevel");
            return r.ok() ? Optional.of(Path.of(r.out().strip())) : Optional.empty();
        } catch (IOException | InterruptedException e) {
            return Optional.empty();
        }
    }

    public Result exec(Duration timeout, String... args) throws IOException, InterruptedException {
        List<String> argv = new ArrayList<>();
        argv.add("git");
        argv.add("-C");
        argv.add(dir.toString());
        argv.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        pb.environment().put("LC_ALL", "C");
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw new IOException("Cannot run git (is it installed and on the PATH?): " + e.getMessage(), e);
        }
        p.getOutputStream().close();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Thread a = drain(p.getInputStream(), out);
        Thread b = drain(p.getErrorStream(), err);
        if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            p.destroyForcibly();
            throw new IOException("git " + String.join(" ", args) + " timed out");
        }
        a.join(5000);
        b.join(5000);
        return new Result(p.exitValue(), out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    /** Output of a command that must succeed. */
    public String run(String... args) throws IOException, InterruptedException {
        Result r = exec(Duration.ofMinutes(2), args);
        if (!r.ok()) {
            throw new IOException("git " + String.join(" ", args) + " failed: " + r.err().strip());
        }
        return r.out();
    }

    public String commit(String ref) throws IOException, InterruptedException {
        return run("rev-parse", "--verify", ref + "^{commit}").strip();
    }

    /** Files of a commit, relative to the repository root. */
    public List<String> files(String ref) throws IOException, InterruptedException {
        return run("ls-tree", "-r", "--name-only", ref).lines().toList();
    }

    public Optional<String> show(String ref, String path) throws IOException, InterruptedException {
        Result r = exec(Duration.ofMinutes(1), "show", ref + ":" + path);
        return r.ok() ? Optional.of(r.out()) : Optional.empty();
    }

    /** Tags matching a glob, newest first. */
    public List<String> tags(String pattern) throws IOException, InterruptedException {
        return run("tag", "--list", pattern, "--sort=-creatordate").lines().filter(l -> !l.isBlank()).toList();
    }

    static boolean isRepo(Path dir) {
        return Files.exists(dir.resolve(".git")) || top(dir).isPresent();
    }

    private static Thread drain(InputStream in, ByteArrayOutputStream into) {
        Thread t = new Thread(() -> {
            try (in) {
                in.transferTo(into);
            } catch (IOException e) {
                // process gone
            }
        }, "git-output");
        t.setDaemon(true);
        t.start();
        return t;
    }
}
