package io.github.elguardian.mcpsettools.commons.process;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Runs a command without a shell: drains its output while it runs, kills the whole process tree
 * on timeout or cancellation, keeps at most {@code maxOutput} characters of each stream.
 */
public final class ProcessRunner {

    /**
     * @param exit     exit code, or -1 when it timed out or was cancelled
     * @param stdout   standard output (stdout and stderr merged with {@code mergeErrors}); empty when redirected to a file
     */
    public record Result(int exit, boolean timedOut, boolean cancelled, String stdout, String stderr, Duration took) {
        public boolean ok() {
            return exit == 0;
        }
    }

    private final List<String> argv;
    private Path dir;
    private final Map<String, String> env = new LinkedHashMap<>();
    private Duration timeout = Duration.ofMinutes(10);
    private Path outputFile;
    private boolean mergeErrors;
    private int maxOutput = 4 * 1024 * 1024;
    private BooleanSupplier cancelled = () -> false;

    private ProcessRunner(List<String> argv) {
        this.argv = List.copyOf(argv);
    }

    public static ProcessRunner of(List<String> argv) {
        return new ProcessRunner(argv);
    }

    public static ProcessRunner of(String... argv) {
        return new ProcessRunner(List.of(argv));
    }

    public ProcessRunner directory(Path dir) {
        this.dir = dir;
        return this;
    }

    public ProcessRunner env(String key, String value) {
        env.put(key, value);
        return this;
    }

    public ProcessRunner env(Map<String, String> values) {
        env.putAll(values);
        return this;
    }

    public ProcessRunner timeout(Duration timeout) {
        this.timeout = timeout;
        return this;
    }

    /** Writes stdout and stderr to this file instead of keeping them. */
    public ProcessRunner outputTo(Path file) {
        this.outputFile = file;
        this.mergeErrors = true;
        return this;
    }

    public ProcessRunner mergeErrors() {
        this.mergeErrors = true;
        return this;
    }

    public ProcessRunner maxOutput(int chars) {
        this.maxOutput = chars;
        return this;
    }

    /** Checked while waiting; when it turns true the process tree is killed. */
    public ProcessRunner cancelledWhen(BooleanSupplier cancelled) {
        this.cancelled = cancelled;
        return this;
    }

    public Result run() throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(new ArrayList<>(argv)).redirectErrorStream(mergeErrors);
        if (dir != null) {
            pb.directory(dir.toFile());
        }
        pb.environment().putAll(env);
        pb.redirectInput(ProcessBuilder.Redirect.from(nullDevice()));
        if (outputFile != null) {
            pb.redirectOutput(outputFile.toFile());
        }
        long start = System.nanoTime();
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw new IOException("Cannot run " + argv.get(0) + ": " + e.getMessage(), e);
        }
        Capture out = outputFile == null ? new Capture(p.getInputStream(), maxOutput) : null;
        Capture err = mergeErrors ? null : new Capture(p.getErrorStream(), maxOutput);
        long deadline = start + timeout.toNanos();
        boolean done = false;
        boolean stopped = false;
        try {
            while (!(done = p.waitFor(Math.min(200, Math.max(1, (deadline - System.nanoTime()) / 1_000_000)), TimeUnit.MILLISECONDS))) {
                if (cancelled.getAsBoolean()) {
                    stopped = true;
                    break;
                }
                if (System.nanoTime() >= deadline) {
                    break;
                }
            }
        } catch (InterruptedException e) {
            kill(p);
            throw e;
        }
        if (!done) {
            kill(p);
        }
        String stdout = out == null ? "" : out.text();
        String stderr = err == null ? "" : err.text();
        return new Result(done ? p.exitValue() : -1, !done && !stopped, stopped, stdout, stderr,
                Duration.ofNanos(System.nanoTime() - start));
    }

    private static void kill(Process p) throws InterruptedException {
        p.descendants().forEach(ProcessHandle::destroy);
        p.destroy();
        if (!p.waitFor(10, TimeUnit.SECONDS)) {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
        }
    }

    private static File nullDevice() {
        return new File(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows") ? "NUL" : "/dev/null");
    }

    /** Reads a stream on a daemon thread, keeping the first {@code max} characters. */
    private static final class Capture {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final Thread thread;

        Capture(InputStream in, int max) {
            thread = new Thread(() -> {
                byte[] buf = new byte[8192];
                try (in) {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        synchronized (bytes) {
                            if (bytes.size() < max) {
                                bytes.write(buf, 0, Math.min(n, max - bytes.size()));
                            }
                        }
                    }
                } catch (IOException e) {
                    // process gone
                }
            }, "process-output");
            thread.setDaemon(true);
            thread.start();
        }

        String text() throws InterruptedException {
            thread.join(5000);
            synchronized (bytes) {
                return bytes.toString(StandardCharsets.UTF_8);
            }
        }
    }
}
