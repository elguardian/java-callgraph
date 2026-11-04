package io.github.elguardian.mcpsettools.mcp;

import io.github.elguardian.mcpsettools.tool.Tool;
import io.github.elguardian.mcpsettools.tool.Schema;
import io.github.elguardian.mcpsettools.tool.ToolArgs;
import io.github.elguardian.mcpsettools.tool.ToolException;
import io.github.elguardian.mcpsettools.tool.Results;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.elguardian.mcpsettools.devtools.junit.ReportReader;
import io.github.elguardian.mcpsettools.devtools.junit.TestCase;
import io.github.elguardian.mcpsettools.devtools.junit.TestRun;
import io.github.elguardian.mcpsettools.commons.stack.StackTrace;
import io.github.elguardian.mcpsettools.commons.stack.StackTraces;

/** Tools over JUnit XML reports (surefire, failsafe, Gradle). */
final class JunitTools {

    static final String PATHS_HELP = "Report files or directories (searched for surefire-reports, failsafe-reports "
            + "and test-results). Default: everything under project_dir";

    private final ReportReader reader = new ReportReader();

    List<Tool> tools() {
        return List.of(summary(), failures(), compare());
    }

    private Tool summary() {
        return new Tool("junit_summary", "Summarize test results", """
                Summarizes JUnit XML reports (Maven surefire/failsafe TEST-*.xml, Gradle test-results): \
                totals, time and counts per module or test class, the slowest tests, failed tests with \
                their exception and the first application frame, failures grouped by cause (same \
                exception types and application frames), and flaky tests (failed, then passed on rerun). \
                Use junit_failures for stack traces and output.""", true,
                inputs(Schema.object()).integer("slowest", "How many slowest tests to list", 10)
                        .choice("group_by", "Per-group totals", "module", "module", "suite", "none")
                        .strings("app_packages", "Application package prefixes, e.g. com.acme (default: all "
                                + "non-JDK, non-framework code)", false)
                        .tableFormat().build(),
                args -> {
                    TestRun run = run(args, "paths");
                    List<String> app = args.strings("app_packages");
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("reports", run.suites().stream().map(s -> s.source().report()).distinct().count());
                    m.put("totals", totals(run.totals()));
                    String groupBy = args.string("group_by", "module");
                    List<Map<String, Object>> groups = new ArrayList<>();
                    if (!groupBy.equals("none")) {
                        (groupBy.equals("suite") ? run.bySuite() : run.byModule()).forEach((k, t) -> {
                            Map<String, Object> g = new LinkedHashMap<>();
                            g.put(groupBy, k);
                            g.putAll(totals(t));
                            groups.add(g);
                        });
                        m.put("by_" + groupBy, groups);
                    }
                    m.put("slowest", run.slowest(args.integer("slowest", 10)).stream().map(c -> {
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("test", c.id());
                        r.put("seconds", c.seconds());
                        r.put("status", Results.lower(c.status()));
                        return r;
                    }).toList());
                    m.put("failures", run.failures().stream().limit(50).map(c -> failureLine(c, app)).toList());
                    m.put("failure_groups", run.failureGroups(app).stream().map(g -> {
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("signature_id", g.id());
                        r.put("count", g.tests().size());
                        r.put("signature", g.signature());
                        r.put("example", g.example());
                        r.put("tests", g.tests().stream().limit(5).map(TestCase::id).toList());
                        return r;
                    }).toList());
                    List<TestCase> flaky = run.flaky();
                    if (!flaky.isEmpty()) {
                        m.put("flaky", flaky.stream().map(c -> c.id() + " (" + c.reruns() + " rerun(s))").toList());
                    }
                    Results.table(m, groups, null, args);
                    return m;
                });
    }

    private Tool failures() {
        return new Tool("junit_failures", "Show test failures", """
                Failed tests (failures and errors; flaky ones with include_flaky) from JUnit XML reports, \
                with the exception, message, a stack trace trimmed of test-runner and reflection frames \
                (or the full one), the failure signature, and optionally the tail of the test's stdout and \
                stderr. table_format renders test, status, exception, message and location.""", true,
                inputs(Schema.object())
                        .string("test", "Only tests whose Class#method contains this text, or matches it as a glob "
                                + "with *", false)
                        .bool("full_stack", "Full stack traces instead of trimmed ones", false)
                        .bool("include_output", "Include the end of the test's stdout/stderr", false)
                        .integer("output_lines", "Lines of output kept from the end", 40)
                        .bool("include_flaky", "Also list flaky tests and their earlier failures", false)
                        .strings("app_packages", "Application package prefixes, e.g. com.acme", false)
                        .integer("limit", "Maximum tests listed", 20).tableFormat().build(),
                args -> {
                    TestRun run = run(args, "paths");
                    List<String> app = args.strings("app_packages");
                    boolean flaky = args.bool("include_flaky", false);
                    String test = args.string("test", null);
                    List<TestCase> selected = run.cases().stream()
                            .filter(c -> c.failed() || flaky && c.status() == TestCase.Status.FLAKY)
                            .filter(c -> TestRun.matches(c, test)).toList();
                    List<Map<String, Object>> rows = new ArrayList<>();
                    List<Map<String, Object>> tableRows = new ArrayList<>();
                    for (TestCase c : selected.subList(0, Math.min(selected.size(), args.integer("limit", 20)))) {
                        rows.add(detail(c, app, args));
                        tableRows.add(failureLine(c, app));
                    }
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("total", selected.size());
                    m.put("items", rows);
                    if (rows.size() < selected.size()) {
                        m.put("truncated", true);
                    }
                    Results.table(m, tableRows, List.of("test", "status", "exception", "message", "where"), args);
                    return m;
                });
    }

    private Tool compare() {
        return new Tool("junit_compare", "Compare two test runs", """
                Compares two sets of JUnit reports (e.g. a copy of target/surefire-reports from before a \
                change, and the current one): tests newly failing, fixed, still failing, added, removed, \
                and tests that became slower. Tests are matched by module and Class#method.""", true,
                Schema.object().strings("baseline", "Reports of the earlier run (files or directories)", true)
                        .strings("current", "Reports of the later run (files or directories)", true)
                        .integer("threshold_percent", "Slower means at least this much more time", 50)
                        .raw("min_seconds", "... and at least this many seconds more", false,
                                Map.of("type", "number", "default", 0.1))
                        .integer("limit", "Maximum tests per list", 50).tableFormat().build(),
                args -> {
                    TestRun before = run(args, "baseline");
                    TestRun after = run(args, "current");
                    double minSeconds = args.raw("min_seconds") instanceof Number n ? n.doubleValue() : 0.1;
                    TestRun.Comparison c = after.compareTo(before, args.integer("threshold_percent", 50), minSeconds);
                    int limit = args.integer("limit", 50);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("baseline", totals(before.totals()));
                    m.put("current", totals(after.totals()));
                    m.put("newly_failing", Results.page(c.newlyFailing().stream().map(t -> failureLine(t, List.of()))
                            .toList(), limit));
                    m.put("fixed", Results.page(c.fixed().stream().map(TestCase::id).toList(), limit));
                    m.put("still_failing", Results.page(c.stillFailing().stream().map(TestCase::id).toList(), limit));
                    m.put("added", Results.page(c.added().stream().map(TestCase::id).toList(), limit));
                    m.put("removed", Results.page(c.removed(), limit));
                    List<Map<String, Object>> slower = c.slower().stream().map(s -> {
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("test", s.test().id());
                        r.put("before_s", s.before());
                        r.put("after_s", s.after());
                        r.put("percent", Double.isInfinite(s.percent()) ? "new time" : Math.round(s.percent()) + "%");
                        return r;
                    }).toList();
                    m.put("slower", Results.page(slower, limit));
                    List<Map<String, Object>> changes = new ArrayList<>();
                    c.newlyFailing().forEach(t -> changes.add(change(t.id(), "newly failing")));
                    c.fixed().forEach(t -> changes.add(change(t.id(), "fixed")));
                    c.stillFailing().forEach(t -> changes.add(change(t.id(), "still failing")));
                    c.added().forEach(t -> changes.add(change(t.id(), "added")));
                    c.removed().forEach(id -> changes.add(change(id, "removed")));
                    c.slower().forEach(s -> changes.add(change(s.test().id(), "slower: " + s.before() + " s -> "
                            + s.after() + " s")));
                    Results.table(m, changes, List.of("test", "change"), args);
                    return m;
                });
    }

    private static Map<String, Object> change(String test, String change) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("test", test);
        m.put("change", change);
        return m;
    }

    private static Schema inputs(Schema s) {
        return s.strings("paths", PATHS_HELP, false)
                .string("project_dir", "Project root searched for reports when paths is not given (default: the "
                        + "server's working directory)", false);
    }

    private TestRun run(ToolArgs args, String pathsArg) throws IOException {
        List<Path> paths = args.paths(pathsArg);
        if (paths.isEmpty()) {
            paths = List.of(ToolArgs.path(args.string("project_dir", ".")));
        }
        TestRun run = new TestRun(reader.read(paths));
        if (run.suites().isEmpty()) {
            throw new ToolException("No JUnit XML reports under " + paths + " (looked for TEST-*.xml in "
                    + "surefire-reports, failsafe-reports and test-results directories)");
        }
        return run;
    }

    private static Map<String, Object> totals(TestRun.Totals t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tests", t.tests());
        m.put("passed", t.passed());
        m.put("failed", t.failed());
        m.put("errors", t.errors());
        m.put("skipped", t.skipped());
        m.put("flaky", t.flaky());
        m.put("seconds", Math.round(t.seconds() * 1000) / 1000.0);
        return m;
    }

    /** One line per failed test: what, where. */
    static Map<String, Object> failureLine(TestCase c, List<String> app) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("test", c.id());
        m.put("module", c.module());
        m.put("status", Results.lower(c.status()));
        TestCase.Problem p = c.mainProblem();
        StackTrace t = TestRun.trace(c);
        if (p != null) {
            m.put("exception", t != null ? t.root().exception() : p.type());
            String msg = t != null && t.root().message() != null ? t.root().message() : p.message();
            if (msg != null) {
                m.put("message", firstLine(msg, 200));
            }
        }
        if (t != null) {
            StackTrace.Frame f = StackTraces.firstAppFrame(t, app);
            if (f != null) {
                m.put("where", f.location() + (f.line() > 0 ? ":" + f.line() : ""));
            }
        }
        return m;
    }

    private static Map<String, Object> detail(TestCase c, List<String> app, ToolArgs args) {
        Map<String, Object> m = failureLine(c, app);
        m.put("seconds", c.seconds());
        TestCase.Problem p = c.mainProblem();
        StackTrace t = TestRun.trace(c);
        if (p != null && p.message() != null && t == null) {
            m.put("full_message", p.message());
        }
        if (t != null) {
            m.put("stack_trace", StackTraces.render(t, !args.bool("full_stack", false), app));
        } else if (p != null && p.stackTrace() != null) {
            m.put("stack_trace", p.stackTrace());
        }
        String signature = TestRun.signature(c, app);
        m.put("signature_id", StackTraces.signatureId(signature));
        if (c.reruns() > 0) {
            m.put("reruns", c.reruns());
        }
        if (args.bool("include_output", false)) {
            int lines = args.integer("output_lines", 40);
            if (c.systemOut() != null) {
                m.put("stdout_tail", tail(c.systemOut(), lines));
            }
            if (c.systemErr() != null) {
                m.put("stderr_tail", tail(c.systemErr(), lines));
            }
        }
        return m;
    }

    static String tail(String text, int lines) {
        List<String> all = text.lines().toList();
        return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
    }

    private static String firstLine(String s, int max) {
        String line = s.lines().findFirst().orElse("").strip();
        return line.length() <= max ? line : line.substring(0, max - 1) + "…";
    }
}
