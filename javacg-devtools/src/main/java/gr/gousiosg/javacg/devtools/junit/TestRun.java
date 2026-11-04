package gr.gousiosg.javacg.devtools.junit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

import gr.gousiosg.javacg.commons.stack.StackTrace;
import gr.gousiosg.javacg.commons.stack.StackTraceParser;
import gr.gousiosg.javacg.commons.stack.StackTraces;

/** The results of a set of reports: totals, slow tests, failures grouped by cause, comparison of two runs. */
public final class TestRun {

    /** Counts over some tests. */
    public record Totals(int tests, int passed, int failed, int errors, int skipped, int flaky, double seconds) {

        static Totals of(List<TestCase> cases, double seconds) {
            int[] n = new int[TestCase.Status.values().length];
            cases.forEach(c -> n[c.status().ordinal()]++);
            return new Totals(cases.size(), n[0], n[1], n[2], n[3], n[4], seconds);
        }

        public boolean ok() {
            return failed == 0 && errors == 0;
        }
    }

    /** Failures with the same signature: probably one cause. */
    public record FailureGroup(String signature, String id, List<TestCase> tests, String example) {
    }

    /** What changed between two runs of the same tests. */
    public record Comparison(List<TestCase> newlyFailing, List<TestCase> fixed, List<TestCase> stillFailing,
            List<TestCase> added, List<String> removed, List<Slower> slower) {
    }

    public record Slower(TestCase test, double before, double after) {
        public double percent() {
            return before <= 0 ? Double.POSITIVE_INFINITY : 100.0 * (after - before) / before;
        }
    }

    private final List<TestSuite> suites;
    private final List<TestCase> cases;

    public TestRun(List<TestSuite> suites) {
        this.suites = List.copyOf(suites);
        this.cases = suites.stream().flatMap(s -> s.cases().stream()).toList();
    }

    public List<TestSuite> suites() {
        return suites;
    }

    public List<TestCase> cases() {
        return cases;
    }

    public Totals totals() {
        return Totals.of(cases, suites.stream().mapToDouble(TestSuite::seconds).sum());
    }

    /** Totals per module, in module order of appearance. */
    public Map<String, Totals> byModule() {
        return grouped(s -> s.source().module());
    }

    /** Totals per suite (test class). */
    public Map<String, Totals> bySuite() {
        return grouped(TestSuite::name);
    }

    private Map<String, Totals> grouped(Function<TestSuite, String> key) {
        Map<String, List<TestSuite>> groups = new LinkedHashMap<>();
        suites.forEach(s -> groups.computeIfAbsent(key.apply(s), k -> new ArrayList<>()).add(s));
        Map<String, Totals> result = new LinkedHashMap<>();
        groups.forEach((k, list) -> result.put(k, Totals.of(list.stream().flatMap(s -> s.cases().stream()).toList(),
                list.stream().mapToDouble(TestSuite::seconds).sum())));
        return result;
    }

    public List<TestCase> slowest(int n) {
        return cases.stream().sorted(Comparator.comparingDouble(TestCase::seconds).reversed()).limit(n).toList();
    }

    public List<TestCase> failures() {
        return cases.stream().filter(TestCase::failed).toList();
    }

    public List<TestCase> flaky() {
        return cases.stream().filter(c -> c.status() == TestCase.Status.FLAKY).toList();
    }

    /** Tests whose {@code Class#name} matches a glob ({@code *} wildcard) or, without {@code *}, contains the text. */
    public static boolean matches(TestCase c, String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return true;
        }
        if (!pattern.contains("*")) {
            return c.id().contains(pattern);
        }
        String regex = String.join(".*", java.util.Arrays.stream(pattern.split("\\*", -1)).map(Pattern::quote).toList());
        return Pattern.compile(regex).matcher(c.id()).matches();
    }

    /** The parsed stack trace of a test's main problem, if it has one. */
    public static StackTrace trace(TestCase c) {
        TestCase.Problem p = c.mainProblem();
        if (p == null || p.stackTrace() == null) {
            return null;
        }
        return StackTraceParser.parse(p.stackTrace()).orElse(null);
    }

    /** Signature of a failed test: its stack trace's, or the problem type and message shape without one. */
    public static String signature(TestCase c, List<String> appPackages) {
        StackTrace t = trace(c);
        if (t != null) {
            return StackTraces.signature(t, appPackages, 3);
        }
        TestCase.Problem p = c.mainProblem();
        if (p == null) {
            return "";
        }
        return (p.type() == null ? "failure" : p.type()) + ": " + template(p.message());
    }

    /** Failures grouped by signature, largest group first. */
    public List<FailureGroup> failureGroups(List<String> appPackages) {
        Map<String, List<TestCase>> groups = new LinkedHashMap<>();
        failures().forEach(c -> groups.computeIfAbsent(signature(c, appPackages), k -> new ArrayList<>()).add(c));
        return groups.entrySet().stream().map(e -> {
            TestCase first = e.getValue().get(0);
            StackTrace t = trace(first);
            String example = t != null ? StackTraces.oneLine(t, appPackages) : firstLine(first.mainProblem().message());
            return new FailureGroup(e.getKey(), StackTraces.signatureId(e.getKey()), e.getValue(), example);
        }).sorted(Comparator.comparingInt((FailureGroup g) -> g.tests().size()).reversed()).toList();
    }

    /**
     * Compares this run with a baseline; a test is slower when it took {@code thresholdPercent}
     * more and at least {@code minSeconds} longer.
     */
    public Comparison compareTo(TestRun baseline, double thresholdPercent, double minSeconds) {
        Map<String, TestCase> before = index(baseline.cases);
        Map<String, TestCase> now = index(cases);
        List<TestCase> newlyFailing = new ArrayList<>();
        List<TestCase> fixed = new ArrayList<>();
        List<TestCase> still = new ArrayList<>();
        List<TestCase> added = new ArrayList<>();
        List<Slower> slower = new ArrayList<>();
        now.forEach((id, c) -> {
            TestCase b = before.get(id);
            if (b == null) {
                added.add(c);
                return;
            }
            if (c.failed() && !b.failed()) {
                newlyFailing.add(c);
            } else if (!c.failed() && b.failed()) {
                fixed.add(c);
            } else if (c.failed()) {
                still.add(c);
            }
            Slower s = new Slower(c, b.seconds(), c.seconds());
            if (c.seconds() - b.seconds() >= minSeconds && s.percent() >= thresholdPercent) {
                slower.add(s);
            }
        });
        List<String> removed = before.keySet().stream().filter(id -> !now.containsKey(id)).sorted().toList();
        slower.sort(Comparator.comparingDouble((Slower s) -> s.after() - s.before()).reversed());
        return new Comparison(newlyFailing, fixed, still, added, removed, slower);
    }

    private static Map<String, TestCase> index(List<TestCase> cases) {
        Map<String, TestCase> m = new LinkedHashMap<>();
        cases.forEach(c -> m.put(c.module() + ":" + c.id(), c));
        return m;
    }

    /** A message with its values replaced by {@code <*>}: {@code expected: <1> but was: <2>} → {@code expected: <*> but was: <*>}. */
    public static String template(String message) {
        return gr.gousiosg.javacg.commons.text.Templates.of(message);
    }

    static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        int nl = s.indexOf('\n');
        return (nl < 0 ? s : s.substring(0, nl)).strip();
    }
}
