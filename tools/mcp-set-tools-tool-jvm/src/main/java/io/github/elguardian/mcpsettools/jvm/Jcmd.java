package io.github.elguardian.mcpsettools.jvm;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Runs {@code jcmd} of the JDK this process runs on against a local JVM. */
public final class Jcmd {

    private Jcmd() {
    }

    public static Path executable() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        Path p = Path.of(System.getProperty("java.home"), "bin", windows ? "jcmd.exe" : "jcmd");
        if (!Files.isExecutable(p)) {
            throw new IllegalStateException("No jcmd in " + p.getParent() + ": the server must run on a JDK, not a JRE");
        }
        return p;
    }

    /**
     * Output of {@code jcmd <pid> <command...>} without the {@code <pid>:} first line.
     *
     * @throws IOException when jcmd fails, times out, or cannot attach
     */
    public static String run(String pid, Duration timeout, String... command) throws IOException, InterruptedException {
        if (!pid.matches("\\d+")) {
            throw new IllegalArgumentException("Not a process id: " + pid);
        }
        List<String> argv = new ArrayList<>();
        argv.add(executable().toString());
        argv.add(pid);
        argv.addAll(List.of(command));
        Process p = new ProcessBuilder(argv).redirectErrorStream(true).start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> copy(p.getInputStream(), out), "jcmd-output");
        reader.setDaemon(true);
        reader.start();
        if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            p.destroyForcibly();
            throw new IOException("jcmd " + pid + " " + String.join(" ", command) + " timed out after " + timeout.toSeconds() + " s");
        }
        reader.join(5000);
        String text = out.toString(StandardCharsets.UTF_8);
        if (p.exitValue() != 0 || text.contains("AttachNotSupportedException") || text.contains("Unable to open socket file")
                || text.contains("com.sun.tools.attach")) {
            throw new IOException("jcmd " + pid + " " + String.join(" ", command) + " failed (exit " + p.exitValue()
                    + "): " + firstLines(text, 5) + " — is it a running JVM of the same user, with attach enabled?");
        }
        String first = pid + ":";
        if (text.startsWith(first)) {
            int nl = text.indexOf('\n');
            text = nl < 0 ? "" : text.substring(nl + 1);
        }
        return text;
    }

    private static void copy(InputStream in, ByteArrayOutputStream out) {
        try (in) {
            in.transferTo(out);
        } catch (IOException e) {
            // process gone
        }
    }

    private static String firstLines(String s, int n) {
        return String.join(" | ", s.lines().limit(n).toList());
    }
}
