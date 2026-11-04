package io.github.elguardian.mcpsettools.mcp;

import io.github.elguardian.mcpsettools.tool.Tool;
import io.github.elguardian.mcpsettools.tool.Schema;
import io.github.elguardian.mcpsettools.tool.ToolArgs;
import io.github.elguardian.mcpsettools.tool.ToolException;
import io.github.elguardian.mcpsettools.tool.Results;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.github.elguardian.mcpsettools.devtools.issues.IssueMatcher;
import io.github.elguardian.mcpsettools.devtools.issues.IssueStore;
import io.github.elguardian.mcpsettools.devtools.issues.JiraCsvImport;
import io.github.elguardian.mcpsettools.devtools.issues.KnownIssue;
import io.github.elguardian.mcpsettools.build.junit.ReportReader;
import io.github.elguardian.mcpsettools.build.junit.TestRun;
import io.github.elguardian.mcpsettools.logs.LogFormat;
import io.github.elguardian.mcpsettools.logs.LogQuery;
import io.github.elguardian.mcpsettools.logs.LogReader;
import io.github.elguardian.mcpsettools.commons.stack.StackTrace;
import io.github.elguardian.mcpsettools.commons.stack.StackTraceParser;
import io.github.elguardian.mcpsettools.commons.stack.StackTraces;

/** Known issues: match traces against a local store and Jira, record new ones, import a Jira export. */
final class IssueTools {

    /** A trace to match and where it came from. */
    private record Input(String from, StackTrace trace) {
    }

    List<Tool> tools() {
        return List.of(match(), add(), importCsv());
    }

    private Tool match() {
        return new Tool("known_issue_match", "Find known issues", """
                Finds known issues behind stack traces: a pasted trace, the error groups of a log file, or the \
                failures of JUnit reports. Each trace is compared with a local store of known issues (a JSON-lines \
                file, shareable in a team repository) and, when JIRA_URL and JIRA_TOKEN are set in the server's \
                environment, with a Jira text search on the root exception and the first application frame whose \
                hits are scored by the stack traces in their description and comments. A score of 1.0 means the \
                same signature (exception types and application frames); lower scores list the reasons (same \
                root exception, frames in common, similar message). Results show the issue status and the \
                versions it was fixed in.""", true,
                Schema.object().string("text", "A stack trace", false)
                        .strings("log", "Log files: each error group's stack trace is matched", false)
                        .string("pattern", "Log format for 'log' (default auto)", false)
                        .strings("junit", "JUnit report files or directories: each failure group is matched", false)
                        .string("store", "Known-issue store (default $MCPSETTOOLS_ISSUES or ~/.mcp-set-tools/known-issues.jsonl)", false)
                        .bool("jira", "Also search Jira (needs JIRA_URL and JIRA_TOKEN in the server's environment)", true)
                        .strings("projects", "Jira project keys to search", false)
                        .bool("save_jira", "Add Jira hits that carry a stack trace to the store", false)
                        .raw("threshold", "Lowest score listed (0 to 1)", false, Map.of("type", "number", "default", 0.3))
                        .integer("limit", "Candidates per trace", 5)
                        .integer("max_traces", "Most input traces matched", 10)
                        .strings("app_packages", "Application package prefixes, e.g. org.acme", false).build(),
                args -> {
                    List<String> app = args.strings("app_packages");
                    List<Input> inputs = inputs(args, app);
                    IssueStore store = store(args);
                    List<KnownIssue> known = store.load();
                    double threshold = args.raw("threshold") instanceof Number n ? n.doubleValue() : 0.3;
                    int limit = args.integer("limit", 5);
                    Optional<JiraClient.Config> jiraConfig = args.bool("jira", true) ? JiraClient.Config.fromEnvironment()
                            : Optional.empty();
                    JiraClient jira = jiraConfig.map(JiraClient::new).orElse(null);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("store", store.file().toString());
                    m.put("store_issues", known.stream().map(KnownIssue::key).distinct().count());
                    m.put("jira", jira != null ? jira.baseUrl() : args.bool("jira", true)
                            ? "not configured: set JIRA_URL and JIRA_TOKEN (and JIRA_USER for Jira Cloud) in the server's environment"
                            : "off");
                    List<KnownIssue> toSave = new ArrayList<>();
                    List<Map<String, Object>> results = new ArrayList<>();
                    for (Input in : inputs) {
                        KnownIssue.Fingerprint fp = KnownIssue.Fingerprint.of(in.trace(), app);
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("from", in.from());
                        r.put("exception", StackTraces.oneLine(in.trace(), app));
                        r.put("signature_id", fp.signatureId());
                        List<Map<String, Object>> candidates = new ArrayList<>();
                        for (IssueMatcher.Candidate c : IssueMatcher.match(fp, known, threshold, limit)) {
                            candidates.add(candidate(c, "store"));
                        }
                        if (jira != null) {
                            try {
                                for (Map<String, Object> c : jira(jira, in.trace(), fp, app, args, threshold, toSave)) {
                                    if (candidates.stream().noneMatch(x -> x.get("key").equals(c.get("key")))) {
                                        candidates.add(c);
                                    }
                                }
                            } catch (java.io.IOException e) {
                                r.put("jira_error", e.getMessage());
                            }
                        }
                        candidates.sort((a, b) -> Double.compare((Double) b.get("score"), (Double) a.get("score")));
                        r.put("candidates", candidates.size() > limit ? candidates.subList(0, limit) : candidates);
                        results.add(r);
                    }
                    m.put("traces", results);
                    if (args.bool("save_jira", false) && !toSave.isEmpty()) {
                        m.put("saved_to_store", store.add(toSave));
                    }
                    return m;
                });
    }

    private List<Map<String, Object>> jira(JiraClient jira, StackTrace trace, KnownIssue.Fingerprint fp, List<String> app,
            ToolArgs args, double threshold, List<KnownIssue> toSave) throws Exception {
        String root = fp.rootException().substring(fp.rootException().lastIndexOf('.') + 1);
        StringBuilder jql = new StringBuilder("text ~ \"\\\"" + root + "\\\"\"");
        StackTrace.Frame f = StackTraces.firstAppFrame(trace, app);
        if (f != null) {
            String cls = f.className().substring(f.className().lastIndexOf('.') + 1).replaceAll("\\$.*", "");
            jql.append(" AND text ~ \"\\\"").append(cls).append('.').append(f.method().replaceAll("^lambda\\$(.+?)\\$\\d+$", "$1"))
                    .append("\\\"\"");
        }
        List<String> projects = args.strings("projects");
        if (!projects.isEmpty()) {
            jql.append(" AND project in (").append(String.join(",", projects)).append(')');
        }
        jql.append(" ORDER BY updated DESC");
        List<Map<String, Object>> out = new ArrayList<>();
        for (JiraClient.Issue issue : jira.search(jql.toString(), 20)) {
            IssueMatcher.Candidate best = null;
            for (StackTrace t : StackTraceParser.parseAll(issue.text().replace("{code}", "\n").replace("{noformat}", "\n"))) {
                KnownIssue k = KnownIssue.of(issue.key(), issue.summary(), issue.status(), issue.fixVersions(), issue.versions(),
                        issue.url(), KnownIssue.Fingerprint.of(t, app), null, "jira");
                IssueMatcher.Candidate c = IssueMatcher.score(fp, k);
                if (best == null || c.score() > best.score()) {
                    best = c;
                }
            }
            if (best != null && best.score() >= threshold) {
                out.add(candidate(best, "jira"));
                toSave.add(best.issue());
            } else if (best == null) {
                KnownIssue k = new KnownIssue(issue.key(), issue.summary(), issue.status(), issue.fixVersions(), issue.versions(),
                        issue.url(), null, null, null, null, List.of(), null, null, "jira");
                out.add(candidate(new IssueMatcher.Candidate(k, 0.2, List.of("text search hit; no stack trace in the issue")),
                        "jira"));
            }
        }
        return out;
    }

    private static Map<String, Object> candidate(IssueMatcher.Candidate c, String from) {
        KnownIssue i = c.issue();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", i.key());
        m.put("score", c.score());
        m.put("title", i.title());
        if (i.status() != null) {
            m.put("status", i.status());
        }
        if (!i.fixedIn().isEmpty()) {
            m.put("fixed_in", i.fixedIn());
        }
        if (!i.affects().isEmpty()) {
            m.put("affects", i.affects());
        }
        if (i.url() != null) {
            m.put("url", i.url());
        }
        m.put("reasons", c.reasons());
        m.put("from", from);
        if (i.notes() != null) {
            m.put("notes", i.notes());
        }
        return m;
    }

    private List<Input> inputs(ToolArgs args, List<String> app) throws Exception {
        List<Input> inputs = new ArrayList<>();
        int max = args.integer("max_traces", 10);
        if (args.has("text")) {
            for (StackTrace t : StackTraceParser.parseAll(args.require("text"))) {
                inputs.add(new Input("text", t));
            }
            if (inputs.isEmpty()) {
                StackTraceParser.parse(args.require("text")).ifPresent(t -> inputs.add(new Input("text", t)));
            }
        }
        if (args.has("log")) {
            List<Path> files = args.paths("log");
            String spec = args.string("pattern", "auto");
            LogFormat format = spec.equals("auto") ? LogFormat.detect(LogReader.head(files.get(0), 200))
                    .orElseThrow(() -> new ToolException("Could not recognize the log format: pass 'pattern'"))
                    : LogFormat.of(spec, null, null);
            LogQuery.Result r = LogQuery.run(files, format, new LogQuery.Filter(null, Set.of(), null, null, null, true, null,
                    null, true, java.time.ZoneId.systemDefault()), new LogQuery.Options(0, false, 0, null, true, 0, app));
            for (LogQuery.ErrorGroup g : r.errors()) {
                StackTrace t = g.example().stackTrace();
                if (t != null) {
                    inputs.add(new Input("log: " + g.count() + " occurrence(s), first at line " + g.example().line(), t));
                }
            }
        }
        if (args.has("junit")) {
            TestRun run = new TestRun(new ReportReader().read(args.paths("junit")));
            for (TestRun.FailureGroup g : run.failureGroups(app)) {
                StackTrace t = TestRun.trace(g.tests().get(0));
                if (t != null) {
                    inputs.add(new Input("test " + g.tests().get(0).id() + (g.tests().size() > 1 ? " and "
                            + (g.tests().size() - 1) + " more" : ""), t));
                }
            }
        }
        if (inputs.isEmpty()) {
            throw new ToolException(args.has("text") || args.has("log") || args.has("junit")
                    ? "No stack trace found in the input" : "Give 'text', 'log' or 'junit'");
        }
        // one input per signature
        Set<String> seen = new LinkedHashSet<>();
        List<Input> unique = new ArrayList<>();
        for (Input in : inputs) {
            if (seen.add(KnownIssue.Fingerprint.of(in.trace(), app).signatureId()) && unique.size() < max) {
                unique.add(in);
            }
        }
        return unique;
    }

    private Tool add() {
        return new Tool("known_issue_add", "Record a known issue", """
                Adds a known issue to the store with the stack trace that identifies it (its signature, root \
                exception and application frames are computed from the trace), so later traces of the same \
                problem match it. Re-adding the same key and trace replaces the entry.""", false,
                Schema.object().string("key", "Issue key, e.g. JBEAP-12345", true)
                        .string("title", "Short description", true)
                        .string("text", "A stack trace of the problem", true)
                        .string("status", "e.g. Open, Resolved", false)
                        .strings("fixed_in", "Versions with the fix", false)
                        .strings("affects", "Affected versions", false)
                        .string("url", "Link to the issue", false)
                        .string("notes", "Workaround, analysis", false)
                        .string("store", "Store file (default $MCPSETTOOLS_ISSUES or ~/.mcp-set-tools/known-issues.jsonl)", false)
                        .strings("app_packages", "Application package prefixes", false).build(),
                args -> {
                    StackTrace t = StackTraceParser.parse(args.require("text"))
                            .orElseThrow(() -> new ToolException("No stack trace found in 'text'"));
                    KnownIssue i = KnownIssue.of(args.require("key"), args.require("title"), args.string("status", null),
                            args.strings("fixed_in"), args.strings("affects"), args.string("url", null),
                            KnownIssue.Fingerprint.of(t, args.strings("app_packages")), args.string("notes", null), "manual");
                    IssueStore store = store(args);
                    boolean fresh = store.add(List.of(i)) == 1;
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("store", store.file().toString());
                    m.put(fresh ? "added" : "replaced", i.key());
                    m.put("signature", i.signature());
                    m.put("signature_id", i.signatureId());
                    m.put("frames", i.frames());
                    return m;
                });
    }

    private Tool importCsv() {
        return new Tool("known_issue_import", "Import known issues from Jira CSV", """
                Imports a Jira CSV export (Issues > Export > CSV, all fields or at least Issue key, Summary, Status, \
                Fix Version/s, Description and Comment) into the known-issue store: one entry per distinct stack \
                trace found in each issue's description and comments.""", false,
                Schema.object().string("csv", "The exported CSV file", true)
                        .string("base_url", "Jira base URL for issue links, e.g. https://issues.redhat.com", false)
                        .string("store", "Store file (default $MCPSETTOOLS_ISSUES or ~/.mcp-set-tools/known-issues.jsonl)", false)
                        .strings("app_packages", "Application package prefixes", false).build(),
                args -> {
                    Path csv = ToolArgs.path(args.require("csv"));
                    if (!Files.isRegularFile(csv)) {
                        throw new ToolException("Not a file: " + csv);
                    }
                    JiraCsvImport.Result r;
                    try {
                        r = JiraCsvImport.read(Files.readString(csv), args.string("base_url", null), args.strings("app_packages"));
                    } catch (IllegalArgumentException e) {
                        throw new ToolException(e.getMessage());
                    }
                    IssueStore store = store(args);
                    int fresh = store.add(r.issues());
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("store", store.file().toString());
                    m.put("rows", r.rows());
                    m.put("rows_without_trace", r.rowsWithoutTrace());
                    m.put("traces_imported", r.issues().size());
                    m.put("new_entries", fresh);
                    m.put("issues", r.issues().stream().map(KnownIssue::key).distinct().toList());
                    return m;
                });
    }

    private static IssueStore store(ToolArgs args) {
        return new IssueStore(args.has("store") ? ToolArgs.path(args.require("store")) : IssueStore.defaultFile());
    }
}
