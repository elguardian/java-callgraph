package io.github.elguardian.mcpsettools.mcp;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

import io.modelcontextprotocol.json.McpJsonDefaults;

/** Fetches pending work from Jira, GitHub and GitLab instances. */
final class StatusSources {

    private StatusSources() {
    }

    static List<WorkItem> fetch(StatusConfig.Instance i, Instant since, String jqlOverride, int max)
            throws IOException, InterruptedException {
        if (i.token() == null) {
            throw new IOException("no token: set " + i.tokenEnv() + " in the MCP server's environment");
        }
        return switch (i.kind()) {
            case "jira" -> jira(i, jqlOverride, max);
            case "github" -> github(i, since, max);
            default -> gitlab(i, since, max);
        };
    }

    // ---- Jira

    static final String DEFAULT_JQL = "assignee = currentUser() AND resolution = Unresolved ORDER BY priority DESC, updated DESC";

    private static List<WorkItem> jira(StatusConfig.Instance i, String jqlOverride, int max) throws IOException, InterruptedException {
        String auth = i.user() != null ? "Basic " + Base64.getEncoder().encodeToString((i.user() + ":" + i.token())
                .getBytes(StandardCharsets.UTF_8)) : "Bearer " + i.token();
        Object me = StatusJson.request("GET", i.url() + "/rest/api/2/myself", auth, null, "Jira " + i.name());
        List<String> myIds = new ArrayList<>();
        for (String k : List.of("name", "key", "accountId", "emailAddress")) {
            if (StatusJson.str(me, k) != null) {
                myIds.add(StatusJson.str(me, k));
            }
        }
        String jql = jqlOverride != null ? jqlOverride : i.jql() != null ? i.jql() : DEFAULT_JQL;
        String query = "?jql=" + URLEncoder.encode(jql, StandardCharsets.UTF_8) + "&maxResults=" + max
                + "&fields=summary,status,priority,issuetype,duedate,updated,created,comment";
        Object body;
        try {
            body = StatusJson.request("GET", i.url() + "/rest/api/2/search" + query, auth, null, "Jira " + i.name());
        } catch (IOException e) {
            if (!e.getMessage().contains("HTTP 404") && !e.getMessage().contains("HTTP 410")) {
                throw e;
            }
            body = StatusJson.request("GET", i.url() + "/rest/api/2/search/jql" + query, auth, null, "Jira " + i.name());
        }
        List<WorkItem> items = new ArrayList<>();
        for (Object issue : StatusJson.list(body, "issues")) {
            String key = StatusJson.str(issue, "key");
            WorkItem w = new WorkItem("jira", i.name(), key);
            Object f = StatusJson.at(issue, "fields");
            w.title = StatusJson.str(f, "summary");
            w.url = i.url() + "/browse/" + key;
            w.roles.add(WorkItem.Role.ASSIGNEE);
            w.state = StatusJson.str(f, "status", "name");
            w.priority = StatusJson.str(f, "priority", "name");
            w.type = StatusJson.str(f, "issuetype", "name");
            w.due = StatusJson.date(StatusJson.at(f, "duedate"));
            w.created = StatusJson.time(StatusJson.at(f, "created"));
            w.updated = StatusJson.time(StatusJson.at(f, "updated"));
            List<Object[]> comments = new ArrayList<>();
            for (Object c : StatusJson.list(f, "comment", "comments")) {
                Object author = StatusJson.at(c, "author");
                boolean mine = myIds.stream().anyMatch(id -> id.equals(StatusJson.str(author, "name"))
                        || id.equals(StatusJson.str(author, "key")) || id.equals(StatusJson.str(author, "accountId")));
                Instant at = StatusJson.time(StatusJson.at(c, "created"));
                if (mine) {
                    w.myLastAction = StatusJson.max(w.myLastAction, at);
                } else {
                    comments.add(new Object[] { StatusJson.str(author, "displayName"), at, StatusJson.str(c, "body") });
                }
            }
            for (Object[] c : comments) {
                Instant at = (Instant) c[1];
                if (at != null && (w.myLastAction == null || at.isAfter(w.myLastAction))) {
                    w.activity.add(new WorkItem.Activity((String) c[0], at, "comment", StatusJson.excerpt((String) c[2])));
                }
            }
            items.add(w);
        }
        return items;
    }

    // ---- GitHub

    private static final String GITHUB_QUERY = """
            query($q1: String!, $q2: String!, $q3: String!, $q4: String!) {
              viewer { login }
              authored: search(query: $q1, type: ISSUE, first: 50) { ...prs }
              review: search(query: $q2, type: ISSUE, first: 50) { ...prs }
              assigned: search(query: $q3, type: ISSUE, first: 50) { ...prs }
              mentioned: search(query: $q4, type: ISSUE, first: 50) { ...prs }
            }
            fragment prs on SearchResultItemConnection {
              nodes { ... on PullRequest {
                number title url isDraft createdAt updatedAt
                repository { nameWithOwner }
                author { login }
                reviewDecision mergeable
                commits(last: 1) { nodes { commit { committedDate statusCheckRollup { state } } } }
                reviews(last: 30) { nodes { author { login } state submittedAt body } }
                reviewThreads(last: 50) { nodes { isResolved comments(last: 1) { nodes { author { login } createdAt body } } } }
                comments(last: 20) { nodes { author { login } createdAt body } }
                reviewRequests(first: 20) { nodes { requestedReviewer { ... on User { login } } } }
              } }
            }""";

    private static List<WorkItem> github(StatusConfig.Instance i, Instant since, int max) throws IOException, InterruptedException {
        String recent = LocalDate.ofInstant(Instant.now().minus(Duration.ofDays(14)), ZoneOffset.UTC).toString();
        String base = "is:open is:pr archived:false ";
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("q1", base + "author:@me");
        vars.put("q2", base + "review-requested:@me");
        vars.put("q3", base + "assignee:@me");
        vars.put("q4", base + "mentions:@me updated:>=" + recent);
        String body = McpJsonDefaults.getMapper().writeValueAsString(Map.of("query", GITHUB_QUERY, "variables", vars));
        String graphql = i.url().endsWith("/api/v3") ? i.url().replaceFirst("/api/v3$", "/api/graphql") : i.url() + "/graphql";
        Object r = StatusJson.request("POST", graphql, "Bearer " + i.token(), body, "GitHub " + i.name());
        if (!StatusJson.list(r, "errors").isEmpty()) {
            throw new IOException("GitHub " + i.name() + ": " + StatusJson.str(StatusJson.list(r, "errors").get(0), "message"));
        }
        String me = StatusJson.str(r, "data", "viewer", "login");
        Map<String, WorkItem> byUrl = new LinkedHashMap<>();
        Map<String, WorkItem.Role> searches = Map.of("authored", WorkItem.Role.AUTHOR, "review", WorkItem.Role.REVIEWER,
                "assigned", WorkItem.Role.ASSIGNEE, "mentioned", WorkItem.Role.MENTIONED);
        for (String alias : List.of("authored", "review", "assigned", "mentioned")) {
            for (Object pr : StatusJson.list(r, "data", alias, "nodes")) {
                String url = StatusJson.str(pr, "url");
                if (url == null || ignored(i, StatusJson.str(pr, "repository", "nameWithOwner"))) {
                    continue;
                }
                WorkItem w = byUrl.computeIfAbsent(url, u -> githubItem(i, pr, me));
                w.roles.add(searches.get(alias));
            }
        }
        List<WorkItem> items = new ArrayList<>(byUrl.values());
        for (WorkItem w : items) {
            finishPullRequest(w, since);
            // a mention needs me when someone wrote @me after my last action
            w.mentionPending = w.roles.contains(WorkItem.Role.MENTIONED) && me != null
                    && w.activity.stream().anyMatch(a -> a.excerpt().contains("@" + me));
        }
        return items.size() > max ? items.subList(0, max) : items;
    }

    private static WorkItem githubItem(StatusConfig.Instance i, Object pr, String me) {
        WorkItem w = new WorkItem("github", i.name(), StatusJson.str(pr, "repository", "nameWithOwner") + "#" + StatusJson.str(pr, "number"));
        w.title = StatusJson.str(pr, "title");
        w.url = StatusJson.str(pr, "url");
        w.draft = StatusJson.bool(pr, "isDraft");
        w.state = w.draft ? "draft" : "open";
        w.created = StatusJson.time(StatusJson.at(pr, "createdAt"));
        w.updated = StatusJson.time(StatusJson.at(pr, "updatedAt"));
        String decision = StatusJson.str(pr, "reviewDecision");
        w.review = decision == null ? null : decision.toLowerCase();
        String mergeable = StatusJson.str(pr, "mergeable");
        w.conflicts = "CONFLICTING".equals(mergeable);
        w.mergeStatus = mergeable == null ? null : mergeable.toLowerCase();
        List<?> commits = StatusJson.list(pr, "commits", "nodes");
        if (!commits.isEmpty()) {
            Object c = StatusJson.at(commits.get(0), "commit");
            w.lastPush = StatusJson.time(StatusJson.at(c, "committedDate"));
            String rollup = StatusJson.str(c, "statusCheckRollup", "state");
            w.ci = rollup == null ? null : switch (rollup) {
                case "SUCCESS" -> "success";
                case "FAILURE", "ERROR" -> "failure";
                default -> "pending";
            };
        }
        boolean author = me != null && me.equals(StatusJson.str(pr, "author", "login"));
        if (author) {
            w.myLastAction = w.lastPush;
        }
        List<Object[]> others = new ArrayList<>();
        Instant myReview = null;
        for (Object rv : StatusJson.list(pr, "reviews", "nodes")) {
            String who = StatusJson.str(rv, "author", "login");
            Instant at = StatusJson.time(StatusJson.at(rv, "submittedAt"));
            if (me != null && me.equals(who)) {
                myReview = StatusJson.max(myReview, at);
                w.myLastAction = StatusJson.max(w.myLastAction, at);
            } else {
                String state = StatusJson.str(rv, "state");
                others.add(new Object[] { who, at, state == null ? "review" : state.toLowerCase(), StatusJson.str(rv, "body") });
            }
        }
        for (Object c : StatusJson.list(pr, "comments", "nodes")) {
            mineOrOther(w, me, c, "comment", others);
        }
        int unresolved = 0;
        for (Object t : StatusJson.list(pr, "reviewThreads", "nodes")) {
            if (!StatusJson.bool(t, "isResolved")) {
                unresolved++;
            }
            for (Object c : StatusJson.list(t, "comments", "nodes")) {
                mineOrOther(w, me, c, "review comment", others);
            }
        }
        w.unresolvedThreads = unresolved;
        for (Object rr : StatusJson.list(pr, "reviewRequests", "nodes")) {
            if (me != null && me.equals(StatusJson.str(rr, "requestedReviewer", "login"))) {
                w.reviewPending = myReview == null || w.lastPush != null && myReview.isBefore(w.lastPush);
            }
        }
        for (Object[] o : others) {
            w.activity.add(new WorkItem.Activity((String) o[0], (Instant) o[1], (String) o[2], StatusJson.excerpt((String) o[3])));
        }
        return w;
    }

    private static void mineOrOther(WorkItem w, String me, Object c, String kind, List<Object[]> others) {
        String who = StatusJson.str(c, "author", "login");
        Instant at = StatusJson.time(StatusJson.at(c, "createdAt"));
        if (me != null && me.equals(who)) {
            w.myLastAction = StatusJson.max(w.myLastAction, at);
        } else {
            others.add(new Object[] { who, at, kind, StatusJson.str(c, "body") });
        }
    }

    /** Keeps the activity by others after my last action (or the window), without bots. */
    private static void finishPullRequest(WorkItem w, Instant since) {
        Instant cutoff = w.myLastAction != null ? w.myLastAction : since;
        w.activity.removeIf(a -> a.at() == null || cutoff != null && !a.at().isAfter(cutoff) || a.author() == null
                || a.author().endsWith("[bot]") || a.author().endsWith("-bot"));
        w.activity.sort((a, b) -> b.at().compareTo(a.at()));
    }

    private static boolean ignored(StatusConfig.Instance i, String repo) {
        for (String g : i.ignore()) {
            if (repo != null && Pattern.matches(g.replace(".", "\\.").replace("*", ".*"), repo)) {
                return true;
            }
        }
        return false;
    }

    // ---- GitLab

    private static List<WorkItem> gitlab(StatusConfig.Instance i, Instant since, int max) throws IOException, InterruptedException {
        String api = i.url() + "/api/v4";
        String auth = "Bearer " + i.token();
        String what = "GitLab " + i.name();
        Object me = StatusJson.request("GET", api + "/user", auth, null, what);
        String myName = StatusJson.str(me, "username");
        String myId = StatusJson.str(me, "id");
        Map<String, WorkItem> byKey = new LinkedHashMap<>();
        Map<String, Object> raw = new LinkedHashMap<>();
        String[][] lists = { { "scope=created_by_me", "AUTHOR" }, { "scope=assigned_to_me", "ASSIGNEE" },
            { "scope=all&reviewer_id=" + myId, "REVIEWER" } };
        for (String[] l : lists) {
            Object mrs = StatusJson.request("GET", api + "/merge_requests?state=opened&per_page=" + max + "&" + l[0], auth, null,
                    what);
            for (Object mr : StatusJson.list(mrs)) {
                add(i, mr, WorkItem.Role.valueOf(l[1]), byKey, raw);
            }
        }
        Object todos = StatusJson.request("GET", api + "/todos?state=pending&type=MergeRequest&per_page=50", auth, null, what);
        for (Object t : StatusJson.list(todos)) {
            Object target = StatusJson.at(t, "target");
            if (target == null || !"opened".equals(StatusJson.str(target, "state"))) {
                continue;
            }
            String action = StatusJson.str(t, "action_name");
            WorkItem w = add(i, target, "review_requested".equals(action) ? WorkItem.Role.REVIEWER : WorkItem.Role.MENTIONED, byKey,
                    raw);
            if (w != null && ("mentioned".equals(action) || "directly_addressed".equals(action))) {
                w.mentionPending = true;
            }
        }
        List<WorkItem> items = new ArrayList<>(byKey.values());
        if (items.size() > max) {
            items = new ArrayList<>(items.subList(0, max));
        }
        List<CompletableFuture<Void>> details = new ArrayList<>();
        for (WorkItem w : items) {
            Object mr = raw.get(w.key());
            details.add(CompletableFuture.runAsync(() -> {
                try {
                    gitlabDetails(api, auth, what, mr, w, myName, since);
                } catch (IOException | InterruptedException e) {
                    w.reasons.add("details unavailable: " + e.getMessage());
                }
            }));
        }
        CompletableFuture.allOf(details.toArray(new CompletableFuture<?>[0])).join();
        return items;
    }

    private static WorkItem add(StatusConfig.Instance i, Object mr, WorkItem.Role role, Map<String, WorkItem> byKey,
            Map<String, Object> raw) {
        String ref = StatusJson.str(mr, "references", "full");
        if (ref == null) {
            ref = StatusJson.str(mr, "project_id") + "!" + StatusJson.str(mr, "iid");
        }
        if (ignored(i, ref.replaceFirst("!.*$", ""))) {
            return null;
        }
        String id = ref;
        WorkItem w = byKey.computeIfAbsent("gitlab:" + i.name() + ":" + id, k -> {
            WorkItem n = new WorkItem("gitlab", i.name(), id);
            n.title = StatusJson.str(mr, "title");
            n.url = StatusJson.str(mr, "web_url");
            n.draft = StatusJson.bool(mr, "draft") || StatusJson.bool(mr, "work_in_progress");
            n.state = n.draft ? "draft" : "open";
            n.created = StatusJson.time(StatusJson.at(mr, "created_at"));
            n.updated = StatusJson.time(StatusJson.at(mr, "updated_at"));
            raw.put(n.key(), mr);
            return n;
        });
        w.roles.add(role);
        return w;
    }

    private static void gitlabDetails(String api, String auth, String what, Object mr, WorkItem w, String me, Instant since)
            throws IOException, InterruptedException {
        String base = api + "/projects/" + StatusJson.str(mr, "project_id") + "/merge_requests/" + StatusJson.str(mr, "iid");
        Object full = StatusJson.request("GET", base, auth, null, what);
        String pipeline = StatusJson.str(full, "head_pipeline", "status");
        w.ci = pipeline == null ? null : switch (pipeline) {
            case "success" -> "success";
            case "failed", "canceled" -> "failure";
            case "skipped", "manual" -> null;
            default -> "pending";
        };
        w.conflicts = StatusJson.bool(full, "has_conflicts");
        w.mergeStatus = StatusJson.str(full, "detailed_merge_status");
        if ("requested_changes".equals(w.mergeStatus)) {
            w.review = "changes_requested";
        }
        boolean author = me != null && me.equals(StatusJson.str(full, "author", "username"));
        // commits come newest first
        List<?> commits = StatusJson.list(StatusJson.request("GET", base + "/commits?per_page=1", auth, null, what));
        if (!commits.isEmpty()) {
            w.lastPush = StatusJson.time(StatusJson.at(commits.get(0), "committed_date"));
        }
        if (author) {
            w.myLastAction = w.lastPush;
        }
        Object approvals = StatusJson.request("GET", base + "/approvals", auth, null, what);
        boolean iApproved = false;
        for (Object a : StatusJson.list(approvals, "approved_by")) {
            if (me != null && me.equals(StatusJson.str(a, "user", "username"))) {
                iApproved = true;
            }
        }
        if (w.review == null) {
            Object left = StatusJson.at(approvals, "approvals_left");
            boolean approved = StatusJson.bool(approvals, "approved") || left instanceof Number n && n.intValue() == 0
                    && !StatusJson.list(approvals, "approved_by").isEmpty();
            w.review = approved ? "approved" : "review_required";
        }
        Object discussions = StatusJson.request("GET", base + "/discussions?per_page=100", auth, null, what);
        int unresolved = 0;
        List<WorkItem.Activity> others = new ArrayList<>();
        for (Object d : StatusJson.list(discussions)) {
            List<?> notes = StatusJson.list(d, "notes");
            boolean open = false;
            for (Object n : notes) {
                if (StatusJson.bool(n, "system")) {
                    continue;
                }
                if (StatusJson.bool(n, "resolvable") && !StatusJson.bool(n, "resolved")) {
                    open = true;
                }
                String who = StatusJson.str(n, "author", "username");
                Instant at = StatusJson.time(StatusJson.at(n, "created_at"));
                if (me != null && me.equals(who)) {
                    w.myLastAction = StatusJson.max(w.myLastAction, at);
                } else {
                    others.add(new WorkItem.Activity(who, at, "comment", StatusJson.excerpt(StatusJson.str(n, "body"))));
                }
            }
            if (open) {
                unresolved++;
            }
        }
        w.unresolvedThreads = unresolved;
        if (w.roles.contains(WorkItem.Role.REVIEWER) && !iApproved) {
            w.reviewPending = w.myLastAction == null || w.lastPush != null && w.myLastAction.isBefore(w.lastPush);
        }
        if (w.mentionPending && w.myLastAction != null && others.stream().noneMatch(a -> a.at() != null
                && a.at().isAfter(w.myLastAction))) {
            w.mentionPending = false;
        }
        w.activity.addAll(others);
        finishPullRequest(w, since);
    }
}
