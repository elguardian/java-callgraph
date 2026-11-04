package io.github.elguardian.mcpsettools.status.tool;

import io.github.elguardian.mcpsettools.tool.Tool;
import io.github.elguardian.mcpsettools.tool.Schema;
import io.github.elguardian.mcpsettools.tool.ToolArgs;
import io.github.elguardian.mcpsettools.tool.ToolException;
import io.github.elguardian.mcpsettools.tool.Results;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** daily_status: pending work across Jira, GitHub and GitLab, ordered by what needs me first. */
final class StatusTools {

    List<Tool> tools() {
        return List.of(dailyStatus());
    }

    private Tool dailyStatus() {
        return new Tool("daily_status", "Daily status of my work", """
                Collects my pending work to plan the day: open Jira issues assigned to me (status, priority, due \
                date, new comments from others), and open GitHub pull requests and GitLab merge requests that I \
                wrote, where my review is requested, that are assigned to me or that mention me (reviews, \
                discussions and comments from others since my last action, unresolved threads, CI, conflicts, \
                approvals). Returns a markdown digest ordered by what needs me first (reviews waiting on me, \
                new feedback, failing CI, conflicts, ready to merge, overdue or urgent issues), then what waits on \
                others and what is in progress, marking items NEW since the last run; plus the items as data. \
                Instances and tokens come from ~/.mcp-set-tools/status.json (tokens named by environment variable) or \
                from JIRA_URL/JIRA_TOKEN, GITHUB_TOKEN, GITLAB_URL/GITLAB_TOKEN in the server's environment. \
                Read only.""", true,
                Schema.object().strings("sources", "Only these kinds: jira, github, gitlab (default all configured)", false)
                        .string("config", "Config file (default $MCPSETTOOLS_STATUS_CONFIG or ~/.mcp-set-tools/status.json)", false)
                        .integer("since_hours", "Window for new activity when there is no saved state", 24)
                        .bool("update_state", "Remember this run, so the next one marks only newer changes as NEW", true)
                        .integer("max_items", "Items per section in the digest", 30)
                        .bool("include_drafts", "Include draft pull/merge requests", true)
                        .string("jira_jql", "JQL instead of the default (assigned to me, unresolved)", false)
                        .bool("digest_only", "Return only the markdown digest", false)
                        .tableFormat().build(),
                args -> {
                    Path configFile = args.has("config") ? ToolArgs.path(args.require("config")) : StatusConfig.defaultFile();
                    StatusConfig config;
                    try {
                        config = StatusConfig.load(configFile);
                    } catch (java.io.IOException e) {
                        throw new ToolException("Cannot read " + configFile + ": " + e.getMessage());
                    }
                    Set<String> kinds = args.has("sources") ? Set.copyOf(args.strings("sources")) : Set.of("jira", "github", "gitlab");
                    List<StatusConfig.Instance> instances = config.instances.stream().filter(i -> kinds.contains(i.kind())).toList();
                    if (instances.isEmpty()) {
                        throw new ToolException("Nothing configured: write " + configFile + " like\n" + StatusConfig.EXAMPLE
                                + "\nand set the named token variables in the MCP server's environment, or set JIRA_URL/JIRA_TOKEN, "
                                + "GITHUB_TOKEN, GITLAB_URL/GITLAB_TOKEN");
                    }
                    StatusState state = StatusState.load(StatusState.defaultFile(config));
                    Instant now = Instant.now();
                    Instant since = state.lastRun != null ? state.lastRun
                            : now.minus(Duration.ofHours(Math.max(1, args.integer("since_hours", 24))));
                    int maxPerSource = 100;
                    Map<String, CompletableFuture<List<WorkItem>>> futures = new LinkedHashMap<>();
                    for (StatusConfig.Instance i : instances) {
                        futures.put(i.kind() + " " + i.name(), CompletableFuture.supplyAsync(() -> {
                            try {
                                return StatusSources.fetch(i, since, args.string("jira_jql", null), maxPerSource);
                            } catch (Exception e) {
                                throw new java.util.concurrent.CompletionException(e);
                            }
                        }));
                    }
                    Map<String, String> sources = new LinkedHashMap<>();
                    List<WorkItem> items = new ArrayList<>();
                    int answered = 0;
                    for (Map.Entry<String, CompletableFuture<List<WorkItem>>> e : futures.entrySet()) {
                        args.progress(answered++, futures.size(), "reading " + e.getKey());
                        try {
                            List<WorkItem> got = e.getValue().get(90, TimeUnit.SECONDS);
                            sources.put(e.getKey(), "ok, " + got.size() + " item(s)");
                            items.addAll(got);
                        } catch (java.util.concurrent.ExecutionException ex) {
                            Throwable c = ex.getCause() instanceof java.util.concurrent.CompletionException ce && ce.getCause() != null
                                    ? ce.getCause() : ex.getCause();
                            sources.put(e.getKey(), "error: " + c.getMessage());
                        } catch (java.util.concurrent.TimeoutException ex) {
                            sources.put(e.getKey(), "error: no answer within 90 s");
                        }
                    }
                    if (!args.bool("include_drafts", true)) {
                        items.removeIf(w -> w.draft);
                    }
                    LocalDate today = LocalDate.now(ZoneId.systemDefault());
                    for (WorkItem w : items) {
                        w.newSinceLastRun = state.lastRun != null && state.changed(w);
                        StatusDigest.score(w, today);
                    }
                    Map<String, List<WorkItem>> sections = StatusDigest.sections(items);
                    String digest = StatusDigest.markdown(sections, sources, args.integer("max_items", 30), ZoneId.systemDefault());
                    if (args.bool("update_state", true) && sources.values().stream().anyMatch(v -> v.startsWith("ok"))) {
                        state.save(items, now);
                    }
                    if (args.bool("digest_only", false)) {
                        return digest;
                    }
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("sources", sources);
                    m.put("since", since.toString());
                    Map<String, Integer> counts = new LinkedHashMap<>();
                    sections.forEach((k, v) -> counts.put(k, v.size()));
                    m.put("counts", counts);
                    m.put("digest", digest);
                    List<Map<String, Object>> needsRows = new ArrayList<>();
                    sections.forEach((k, v) -> {
                        List<Map<String, Object>> views = v.stream().map(StatusTools::view).toList();
                        m.put(k, views);
                        if (k.equals("needs_you")) {
                            v.forEach(w -> {
                                Map<String, Object> r = new LinkedHashMap<>();
                                r.put("item", w.id);
                                r.put("title", w.title);
                                r.put("why", String.join(", ", w.reasons));
                                r.put("new", w.newSinceLastRun ? "NEW" : "");
                                needsRows.add(r);
                            });
                        }
                    });
                    Results.table(m, needsRows, null, args);
                    return m;
                });
    }

    private static Map<String, Object> view(WorkItem w) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", w.id);
        m.put("source", w.source + " " + w.instance);
        m.put("title", w.title);
        m.put("url", w.url);
        if (w.newSinceLastRun) {
            m.put("new", true);
        }
        if (!w.reasons.isEmpty()) {
            m.put("why", w.reasons);
        }
        m.put("score", w.score);
        if (w.isPullRequest()) {
            m.put("roles", StatusDigest.roles(w));
            m.put("state", StatusDigest.prState(w));
            if (w.conflicts) {
                m.put("conflicts", true);
            }
        } else {
            m.put("status", w.state);
            if (w.priority != null) {
                m.put("priority", w.priority);
            }
            if (w.type != null) {
                m.put("type", w.type);
            }
            if (w.due != null) {
                m.put("due", w.due.toString());
            }
        }
        if (w.updated != null) {
            m.put("updated", w.updated.toString());
        }
        if (!w.activity.isEmpty()) {
            m.put("new_activity", w.activity.stream().limit(5).map(a -> {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("author", a.author());
                r.put("kind", a.kind());
                r.put("at", a.at().toString());
                r.put("text", a.excerpt());
                return r;
            }).toList());
        }
        return m;
    }
}
