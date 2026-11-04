package io.github.elguardian.mcpsettools.testkit;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** Runs a main class in a separate JVM (the one running the tests) and captures its output. */
public final class ForkedJvm {

    public record Result(int exitCode, String stdout, String stderr) {
        public Result assertSuccess() {
            if (exitCode != 0) {
                throw new AssertionError("Forked JVM exited with " + exitCode
                        + "\n--- stdout ---\n" + stdout + "\n--- stderr ---\n" + stderr);
            }
            return this;
        }
    }

    private final List<Path> classpath = new ArrayList<>();
    private final List<String> jvmArgs = new ArrayList<>();
    private final List<String> args = new ArrayList<>();
    private String mainClass;
    private Path workDir;
    private Duration timeout = Duration.ofMinutes(1);

    private ForkedJvm() {
    }

    public static ForkedJvm create() {
        return new ForkedJvm();
    }

    public ForkedJvm classpath(Path... entries) {
        classpath.addAll(List.of(entries));
        return this;
    }

    public ForkedJvm jvmArg(String... a) {
        jvmArgs.addAll(List.of(a));
        return this;
    }

    public ForkedJvm mainClass(String name) {
        this.mainClass = name;
        return this;
    }

    public ForkedJvm arg(String... a) {
        args.addAll(List.of(a));
        return this;
    }

    public ForkedJvm workDir(Path dir) {
        this.workDir = dir;
        return this;
    }

    public ForkedJvm timeout(Duration t) {
        this.timeout = t;
        return this;
    }

    public Result run() throws IOException, InterruptedException {
        if (mainClass == null) {
            throw new IllegalStateException("mainClass not set");
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        cmd.addAll(jvmArgs);
        if (!classpath.isEmpty()) {
            cmd.add("-cp");
            cmd.add(classpath.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator)));
        }
        cmd.add(mainClass);
        cmd.addAll(args);

        Path out = Files.createTempFile("mcp-set-tools-fork", ".out");
        Path err = Files.createTempFile("mcp-set-tools-fork", ".err");
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectOutput(out.toFile()).redirectError(err.toFile());
            if (workDir != null) {
                pb.directory(workDir.toFile());
            }
            Process p = pb.start();
            if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                p.destroyForcibly().waitFor();
                throw new AssertionError("Forked JVM timed out after " + timeout + ": " + cmd
                        + "\n--- stdout ---\n" + Files.readString(out, StandardCharsets.UTF_8)
                        + "\n--- stderr ---\n" + Files.readString(err, StandardCharsets.UTF_8));
            }
            return new Result(p.exitValue(), Files.readString(out, StandardCharsets.UTF_8),
                    Files.readString(err, StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(out);
            Files.deleteIfExists(err);
        }
    }
}
