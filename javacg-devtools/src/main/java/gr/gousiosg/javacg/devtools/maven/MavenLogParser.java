package gr.gousiosg.javacg.devtools.maven;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import gr.gousiosg.javacg.devtools.logs.LogFormat;
import gr.gousiosg.javacg.devtools.logs.LogRecord;
import gr.gousiosg.javacg.devtools.logs.Templates;

/**
 * Reads Maven 3.9 / 4 console output ({@code -B}, with or without timestamps and thread names
 * from {@code -Dorg.slf4j.simpleLogger.showDateTime/showThreadName}): modules and their
 * status/time, plugin executions, compiler messages, surefire/failsafe results, goal failures,
 * the resume hint, warnings and remaining errors.
 */
public final class MavenLogParser {

    private static final Pattern ANSI = Pattern.compile("\u001B\\[[;\\d]*[A-Za-z]");
    private static final Pattern GAV = Pattern.compile("^-+< (\\S+):(\\S+) >-+$");
    private static final Pattern BUILDING = Pattern.compile("^Building (.+?) (\\S+)\\s+\\[(\\d+)/(\\d+)]$");
    private static final Pattern EXECUTION = Pattern.compile("^--- (\\S+?):(\\S+?):(\\S+) \\(([^)]*)\\) @ (\\S+) ---$");
    private static final Pattern SUMMARY_LINE = Pattern.compile(
            "^(.+?) \\.+ ?(SUCCESS|FAILURE|SKIPPED)(?:\\s+\\[\\s*([^\\]]+?)\\s*])?\\s*$");
    private static final Pattern COMPILER = Pattern.compile(
            "^((?:[A-Za-z]:)?[/\\\\]\\S.*?\\.(?:java|kt|groovy|scala)):\\[(\\d+),(\\d+)] (.*)$");
    private static final Pattern TESTS_RUN = Pattern.compile(
            "^Tests run: (\\d+), Failures: (\\d+), Errors: (\\d+), Skipped: (\\d+)(?:, Flakes: \\d+)?"
                    + "(?:, Time elapsed: [^<]*?)?(?:\\s*<<< (?:FAILURE|ERROR)!)?(?:\\s*--? in (\\S+))?\\s*$");
    private static final Pattern TEST_FAILED = Pattern.compile(
            "^(\\S+?)(?:\\(([^)]+)\\))?(?:\\s+--)?\\s+Time elapsed: ([\\d.,]+) s(?:ec)?\\s+<<< (FAILURE|ERROR)!$");
    private static final Pattern GOAL_FAILED = Pattern.compile(
            "^Failed to execute goal(?: (\\S+) \\(([^)]*)\\))? on project ([^:]+): (.*)$");
    private static final Pattern ORDER_LINE = Pattern.compile("^(.+?)\\s+\\[([\\w.-]+)]$");
    private static final Pattern RESUME = Pattern.compile("^\\s*mvn <args> -rf (\\S+)\\s*$");
    private static final Pattern TOTAL_TIME = Pattern.compile("^Total time:\\s+(.+?)\\s*(?:\\(Wall Clock\\))?$");
    private static final Pattern FINISHED = Pattern.compile("^Finished at:\\s+(.+)$");
    private static final List<String> BOILERPLATE = List.of("-> [Help", "[Help ", "To see the full stack trace",
            "Re-run Maven using", "For more information about the errors", "After correcting the problems",
            "See dump files", "See ", "http://cwiki.apache.org", "https://cwiki.apache.org", "COMPILATION ERROR",
            "COMPILATION WARNING", "Failures: ", "Errors: ", "Flakes: ");

    private static final LogFormat LINE = LogFormat.of("maven", null, ZoneOffset.UTC.getId());

    private final MavenBuild build = new MavenBuild();
    private final Map<String, Acc> modules = new LinkedHashMap<>();
    private final Map<String, String> currentByThread = new HashMap<>();
    private final Map<String, Integer> warningCounts = new LinkedHashMap<>();
    private final Map<String, String[]> warningExamples = new HashMap<>();
    private final Set<String> compilerSeen = new LinkedHashSet<>();
    private final Set<String> errorsSeen = new LinkedHashSet<>();
    private String pendingGroup;
    private String pendingArtifact;
    private boolean inSummary;
    private boolean inOrder;
    private String summaryBlock;
    private MavenBuild.CompilerMessage lastCompiler;
    private TestAcc openTest;

    private static final class Acc {
        String artifactId;
        String name;
        int index;
        String status;
        double seconds = -1;
        long start = Long.MIN_VALUE;
        long end = Long.MIN_VALUE;
        final Map<String, Integer> threads = new LinkedHashMap<>();
        final List<String> executions = new ArrayList<>();

        MavenBuild.Module build() {
            String thread = threads.entrySet().stream().filter(e -> !e.getKey().equals("main"))
                    .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey)
                    .orElse(threads.isEmpty() ? null : threads.keySet().iterator().next());
            return new MavenBuild.Module(artifactId, name == null ? artifactId : name, index, status, seconds, start, end,
                    thread, executions);
        }
    }

    private static final class TestAcc {
        final String module;
        final String test;
        final String kind;
        final double seconds;
        final StringBuilder trace = new StringBuilder();

        TestAcc(String module, String test, String kind, double seconds) {
            this.module = module;
            this.test = test;
            this.kind = kind;
            this.seconds = seconds;
        }
    }

    private MavenLogParser() {
    }

    public static MavenBuild parse(Path log) throws IOException {
        try (BufferedReader r = Files.newBufferedReader(log, StandardCharsets.UTF_8)) {
            return parse(r);
        }
    }

    public static MavenBuild parse(String text) {
        try {
            return parse(new BufferedReader(new StringReader(text)));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static MavenBuild parse(BufferedReader r) throws IOException {
        MavenLogParser p = new MavenLogParser();
        String line;
        while ((line = r.readLine()) != null) {
            p.line(ANSI.matcher(line).replaceAll(""));
        }
        return p.finish();
    }

    private void line(String raw) {
        build.lines++;
        Optional<LogRecord> rec = LINE.parseLine(raw, null, build.lines);
        if (rec.isEmpty()) {
            continuation(raw);
            return;
        }
        LogRecord r = rec.get();
        String level = r.level() == null ? "INFO" : r.level();
        String thread = r.thread() == null ? "main" : r.thread();
        String text = r.message();
        String body = text.strip();
        // a level-prefixed line ends the stack trace of a failed test
        closeTest();
        Acc current = current(thread);
        if (current != null && r.hasTime()) {
            if (current.start == Long.MIN_VALUE) {
                current.start = r.micros();
            }
            current.end = r.micros();
            current.threads.merge(thread, 1, Integer::sum);
        }
        Matcher m;
        if ((m = GAV.matcher(body)).matches()) {
            pendingGroup = m.group(1);
            pendingArtifact = m.group(2);
            return;
        }
        if ((m = BUILDING.matcher(body)).matches()) {
            Acc a = module(pendingArtifact != null ? pendingArtifact : m.group(1));
            a.artifactId = pendingArtifact;
            a.name = m.group(1);
            a.index = Integer.parseInt(m.group(3));
            pendingArtifact = null;
            pendingGroup = null;
            currentByThread.put(thread, key(a));
            touch(a, r, thread);
            return;
        }
        if ((m = EXECUTION.matcher(body)).matches()) {
            Acc a = module(m.group(5));
            if (a.artifactId == null) {
                a.artifactId = m.group(5);
            }
            a.executions.add(m.group(1) + ":" + m.group(3) + " (" + m.group(4) + ")");
            currentByThread.put(thread, key(a));
            touch(a, r, thread);
            return;
        }
        if (body.equals("Reactor Build Order:")) {
            inOrder = true;
            return;
        }
        if (inOrder) {
            if ((m = ORDER_LINE.matcher(body)).matches()) {
                build.reactorOrder.add(m.group(1) + " [" + m.group(2) + "]");
                return;
            }
            if (!body.isEmpty() || !build.reactorOrder.isEmpty()) {
                inOrder = false;
            }
        }
        if (body.startsWith("Reactor Summary")) {
            inSummary = true;
            return;
        }
        if (inSummary && (m = SUMMARY_LINE.matcher(body)).matches()) {
            Acc a = byName(m.group(1).strip());
            a.status = m.group(2);
            if (m.group(3) != null) {
                a.seconds = seconds(m.group(3));
            }
            return;
        }
        if (body.equals("BUILD SUCCESS") || body.equals("BUILD FAILURE")) {
            inSummary = false;
            build.status = body.endsWith("SUCCESS") ? MavenBuild.Status.SUCCESS : MavenBuild.Status.FAILURE;
            return;
        }
        if ((m = TOTAL_TIME.matcher(body)).matches()) {
            build.totalTime = m.group(1);
            build.totalSeconds = seconds(m.group(1));
            return;
        }
        if ((m = FINISHED.matcher(body)).matches()) {
            build.finishedAt = m.group(1);
            return;
        }
        String module = current == null ? null : key(current);
        if ((m = COMPILER.matcher(body)).matches() && (level.equals("ERROR") || level.equals("WARN"))) {
            MavenBuild.CompilerMessage c = new MavenBuild.CompilerMessage(level.equals("ERROR") ? "error" : "warning",
                    module, m.group(1), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), m.group(4));
            String id = c.severity() + c.file() + c.line() + c.column() + c.message();
            if (compilerSeen.add(id)) {
                (level.equals("ERROR") ? build.compilerErrors : build.compilerWarnings).add(c);
                lastCompiler = c;
            } else {
                // Maven repeats compiler errors in the goal failure; details go to the first copy
                lastCompiler = compilerList(c).stream().filter(x -> x.file().equals(c.file()) && x.line() == c.line()
                        && x.column() == c.column() && x.message().startsWith(c.message())).findFirst().orElse(null);
            }
            return;
        }
        if (lastCompiler != null && text.startsWith("  ") && compilerDetail(body)) {
            return;
        }
        lastCompiler = null;
        if ((m = TESTS_RUN.matcher(body)).matches()) {
            int failures = Integer.parseInt(m.group(2));
            int errors = Integer.parseInt(m.group(3));
            String cls = m.group(5);
            if (cls == null || failures + errors > 0) {
                build.testCounts.add(new MavenBuild.TestCounts(module, cls, Integer.parseInt(m.group(1)), failures, errors,
                        Integer.parseInt(m.group(4))));
            }
            summaryBlock = null;
            return;
        }
        if ((m = TEST_FAILED.matcher(body)).matches()) {
            String test = m.group(2) != null ? m.group(2) + "." + m.group(1) : m.group(1);
            openTest = new TestAcc(module, test, m.group(4).toLowerCase(java.util.Locale.ROOT), seconds(m.group(3)));
            return;
        }
        if (level.equals("ERROR") && (body.equals("Failures:") || body.equals("Errors:")) || level.equals("WARN")
                && body.equals("Flakes:")) {
            summaryBlock = body.substring(0, body.length() - 1).toLowerCase(java.util.Locale.ROOT);
            return;
        }
        if (summaryBlock != null && text.startsWith("  ") && !body.isEmpty()) {
            build.testSummary.add(summaryBlock + ": " + body);
            return;
        }
        if (summaryBlock != null && !text.startsWith("  ")) {
            summaryBlock = null;
        }
        if ((m = GOAL_FAILED.matcher(body)).matches()) {
            build.goalFailures.add(new MavenBuild.GoalFailure(m.group(1), m.group(2), m.group(3), m.group(4)));
            return;
        }
        if ((m = RESUME.matcher(body)).matches()) {
            build.resumeFrom = m.group(1);
            return;
        }
        if (level.equals("WARN") && !body.isEmpty()) {
            String t = Templates.of(body);
            warningCounts.merge(t, 1, Integer::sum);
            warningExamples.putIfAbsent(t, new String[] { body, module });
            return;
        }
        if (level.equals("ERROR") && !body.isEmpty() && BOILERPLATE.stream().noneMatch(body::startsWith)
                && errorsSeen.size() < 200) {
            errorsSeen.add(body);
        }
    }

    /** Lines without a level: stack traces of failed tests, javac details, multi-line plugin output. */
    private void continuation(String raw) {
        if (lastCompiler != null && raw.startsWith("  ") && compilerDetail(raw.strip())) {
            return;
        }
        if (openTest != null) {
            if (raw.isBlank()) {
                closeTest();
            } else if (openTest.trace.length() < 64 * 1024) {
                openTest.trace.append(raw).append('\n');
            }
        }
    }

    /** Appends a javac detail line ({@code symbol:}, {@code location:}, ...) to the last compiler message. */
    private boolean compilerDetail(String body) {
        if (!(body.startsWith("symbol:") || body.startsWith("location:") || body.startsWith("required:")
                || body.startsWith("found:") || body.startsWith("reason:"))) {
            return false;
        }
        if (!lastCompiler.message().contains("\n" + body)) {
            List<MavenBuild.CompilerMessage> list = compilerList(lastCompiler);
            int i = list.indexOf(lastCompiler);
            lastCompiler = new MavenBuild.CompilerMessage(lastCompiler.severity(), lastCompiler.module(), lastCompiler.file(),
                    lastCompiler.line(), lastCompiler.column(), lastCompiler.message() + "\n" + body);
            list.set(i, lastCompiler);
        }
        return true;
    }

    private List<MavenBuild.CompilerMessage> compilerList(MavenBuild.CompilerMessage c) {
        return c.severity().equals("error") ? build.compilerErrors : build.compilerWarnings;
    }

    private void closeTest() {
        if (openTest != null) {
            build.testFailures.add(new MavenBuild.TestFailure(openTest.module, openTest.test, openTest.kind, openTest.seconds,
                    openTest.trace.toString().strip()));
            openTest = null;
        }
    }

    private Acc current(String thread) {
        String k = currentByThread.get(thread);
        return k == null ? null : modules.get(k);
    }

    private static void touch(Acc a, LogRecord r, String thread) {
        if (r.hasTime()) {
            if (a.start == Long.MIN_VALUE) {
                a.start = r.micros();
            }
            a.end = r.micros();
            a.threads.merge(thread, 1, Integer::sum);
        }
    }

    private Acc module(String key) {
        return modules.computeIfAbsent(key, k -> new Acc());
    }

    private static String key(Acc a) {
        return a.artifactId != null ? a.artifactId : a.name;
    }

    /** A module by the name the reactor summary prints (the project name). */
    private Acc byName(String name) {
        for (Acc a : modules.values()) {
            if (name.equals(a.name) || name.equals(a.artifactId)) {
                return a;
            }
        }
        Acc a = module(name);
        a.name = name;
        return a;
    }

    private MavenBuild finish() {
        closeTest();
        modules.values().forEach(a -> build.modules.put(key(a), a.build()));
        warningCounts.forEach((t, n) -> {
            String[] ex = warningExamples.get(t);
            build.warnings.add(new MavenBuild.Repeated(t, n, ex[0], ex[1]));
        });
        build.warnings.sort((a, b) -> Integer.compare(b.count(), a.count()));
        build.errors.addAll(errorsSeen);
        return build;
    }

    /** {@code 1.234 s}, {@code 01:05 min}, {@code 01:02 h}. */
    static double seconds(String s) {
        String t = s.strip().replace(',', '.');
        try {
            if (t.endsWith(" min") || t.endsWith(" h")) {
                String[] p = t.substring(0, t.indexOf(' ')).split(":");
                double v = 0;
                for (String x : p) {
                    v = v * 60 + Double.parseDouble(x);
                }
                return t.endsWith(" h") ? v * 60 : v;
            }
            return Double.parseDouble(t.replaceAll("\\s*(s|sec)$", ""));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
