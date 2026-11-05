package io.github.elguardian.mcpsettools.build.maven;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs Maven in a project and parses its output. Uses the project's {@code mvnw} when there is
 * one, else {@code $MAVEN_HOME/bin/mvn}, else {@code mvn} on the {@code PATH}; no shell is
 * involved. Batch mode and no transfer progress are always on, and log lines get a timestamp and
 * the builder thread so the build can be drawn as a timeline.
 */
public final class MavenRunner {

    /** Flags making Maven 3 (slf4j simple logger) and Maven 4 print time and thread on each line. */
    public static final List<String> TIMESTAMP_FLAGS = List.of("-Dorg.slf4j.simpleLogger.showDateTime=true",
            "-Dorg.slf4j.simpleLogger.dateTimeFormat=HH:mm:ss.SSS", "-Dorg.slf4j.simpleLogger.showThreadName=true",
            "-Dmaven.logger.showDateTime=true", "-Dmaven.logger.dateTimeFormat=HH:mm:ss.SSS",
            "-Dmaven.logger.showThreadName=true");

    /**
     * @param threads Maven {@code -T} value (e.g. {@code 4}, {@code 1C}), or null
     * @param logFile where the output goes; null: a new file under the temp directory (not under
     *                {@code target/}, which {@code clean} would delete during the build)
     */
    public record Options(Path projectDir, List<String> args, Duration timeout, boolean offline, String threads,
            Map<String, String> env, Path logFile, boolean timestamps) {
    }

    public record Run(List<String> command, int exitCode, boolean timedOut, Duration took, Path log, MavenBuild build) {
    }

    /** Told about each module as Maven starts building it ({@code index} from 1). */
    @FunctionalInterface
    public interface Progress {
        void module(int index, int total, String name);
    }

    /** {@code Building <name> <version> [n/m]}, the reactor's per-module header. */
    private static final Pattern BUILDING = Pattern.compile("\\bBuilding (.+?)\\s+\\[(\\d+)/(\\d+)\\]\\s*$");

    private MavenRunner() {
    }

    public static Run run(Options o) throws IOException, InterruptedException {
        return run(o, null);
    }

    /** Runs Maven; with {@code progress}, follows the log while it runs. */
    public static Run run(Options o, Progress progress) throws IOException, InterruptedException {
        Path dir = o.projectDir().toAbsolutePath().normalize();
        if (!Files.isRegularFile(dir.resolve("pom.xml"))) {
            throw new IllegalArgumentException("No pom.xml in " + dir);
        }
        List<String> command = new ArrayList<>();
        command.add(executable(dir).toString());
        command.add("-B");
        command.add("-ntp");
        if (o.offline()) {
            command.add("-o");
        }
        if (o.threads() != null && !o.threads().isBlank()) {
            command.add("-T");
            command.add(o.threads().strip());
        }
        if (o.timestamps()) {
            command.addAll(TIMESTAMP_FLAGS);
        }
        command.addAll(o.args());
        Path log = o.logFile() != null ? o.logFile() : defaultLog(dir);
        Files.createDirectories(log.toAbsolutePath().getParent());
        ProcessBuilder pb = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true)
                .redirectOutput(log.toFile()).redirectInput(ProcessBuilder.Redirect.from(nullFile()));
        pb.environment().putAll(o.env());
        long start = System.nanoTime();
        Process p = pb.start();
        boolean done;
        if (progress == null) {
            done = p.waitFor(o.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } else {
            long deadline = start + o.timeout().toNanos();
            LogFollower follower = new LogFollower(log, progress);
            do {
                done = p.waitFor(Math.max(1, Math.min(1000, (deadline - System.nanoTime()) / 1_000_000)), TimeUnit.MILLISECONDS);
                follower.poll();
            } while (!done && System.nanoTime() < deadline);
        }
        if (!done) {
            p.descendants().forEach(ProcessHandle::destroy);
            p.destroy();
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
            }
        }
        Duration took = Duration.ofNanos(System.nanoTime() - start);
        return new Run(command, done ? p.exitValue() : -1, !done, took, log, MavenLogParser.parse(log));
    }

    /** Reads the lines appended to a log since the last poll and reports module headers. */
    static final class LogFollower {
        private final Path log;
        private final Progress progress;
        private long offset;
        private final StringBuilder partial = new StringBuilder();

        LogFollower(Path log, Progress progress) {
            this.log = log;
            this.progress = progress;
        }

        void poll() {
            try (var ch = Files.newByteChannel(log)) {
                long size = ch.size();
                if (size <= offset) {
                    return;
                }
                ch.position(offset);
                var buf = java.nio.ByteBuffer.allocate((int) Math.min(size - offset, 4 << 20));
                while (buf.hasRemaining() && ch.read(buf) > 0) {
                    // fill
                }
                offset += buf.position();
                partial.append(new String(buf.array(), 0, buf.position(), java.nio.charset.StandardCharsets.UTF_8));
            } catch (IOException e) {
                return;
            }
            int nl;
            while ((nl = partial.indexOf("\n")) >= 0) {
                line(partial.substring(0, nl));
                partial.delete(0, nl + 1);
            }
        }

        void line(String line) {
            Matcher m = BUILDING.matcher(line);
            if (m.find()) {
                progress.module(Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), m.group(1).strip());
            }
        }
    }

    /** {@code mvnw} in the project or a parent directory, {@code $MAVEN_HOME/bin/mvn}, or {@code mvn} on the PATH. */
    public static Path executable(Path projectDir) {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        String wrapper = windows ? "mvnw.cmd" : "mvnw";
        for (Path d = projectDir; d != null; d = d.getParent()) {
            Path w = d.resolve(wrapper);
            if (Files.isRegularFile(w) && Files.isExecutable(w) && Files.isDirectory(d.resolve(".mvn"))) {
                return w;
            }
            if (Files.exists(d.resolve(".git"))) {
                break;
            }
        }
        String mvn = windows ? "mvn.cmd" : "mvn";
        for (String home : new String[] { System.getenv("MAVEN_HOME"), System.getenv("M2_HOME") }) {
            if (home != null && Files.isExecutable(Path.of(home, "bin", mvn))) {
                return Path.of(home, "bin", mvn);
            }
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String d : path.split(File.pathSeparator)) {
                Path candidate = Path.of(d, mvn);
                if (Files.isExecutable(candidate)) {
                    return candidate;
                }
            }
        }
        throw new IllegalStateException("No Maven found: no mvnw in the project, MAVEN_HOME not set and no " + mvn
                + " on the PATH");
    }

    private static Path defaultLog(Path dir) {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
        return Path.of(System.getProperty("java.io.tmpdir"), "mcp-set-tools-mcp", "maven",
                dir.getFileName() + "-" + stamp + ".log");
    }

    private static File nullFile() {
        return new File(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows") ? "NUL"
                : "/dev/null");
    }
}
