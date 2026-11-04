package io.github.elguardian.mcpsettools.mcp;

import io.github.elguardian.mcpsettools.testkit.ToolHarness;
import static io.github.elguardian.mcpsettools.testkit.ToolHarness.error;
import static io.github.elguardian.mcpsettools.testkit.ToolHarness.map;
import static io.github.elguardian.mcpsettools.testkit.ToolHarness.ok;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.modelcontextprotocol.json.McpJsonDefaults;

/** daily_status against a stub Jira, GitHub GraphQL and two GitLab instances (one refuses the token). */
class StatusToolsTest {

    @TempDir
    Path tmp;

    HttpServer server;
    Path config;
    final List<String> requests = new ArrayList<>();
    volatile String acme2Updated = ago(Duration.ofDays(20));

    static String ago(Duration d) {
        return Instant.now().minus(d).toString();
    }

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        config = tmp.resolve("status.json");
        Files.writeString(config, """
                {"jira": [{"url": "%1$s/jira", "token_env": "TEST_JIRA_TOKEN", "name": "jira"}],
                 "github": [{"api_url": "%1$s/github", "token_env": "TEST_GH_TOKEN", "name": "github"}],
                 "gitlab": [{"url": "%1$s/gl1", "token_env": "TEST_GL1_TOKEN", "name": "work"},
                            {"url": "%1$s/gl2", "token_env": "TEST_GL2_TOKEN", "name": "other"}]}
                """.formatted(base));
        System.setProperty("TEST_JIRA_TOKEN", "jt");
        System.setProperty("TEST_GH_TOKEN", "gt");
        System.setProperty("TEST_GL1_TOKEN", "l1");
        System.setProperty("TEST_GL2_TOKEN", "l2");
    }

    @AfterEach
    void stop() {
        server.stop(0);
        for (String p : List.of("TEST_JIRA_TOKEN", "TEST_GH_TOKEN", "TEST_GL1_TOKEN", "TEST_GL2_TOKEN")) {
            System.clearProperty(p);
        }
    }

    private void handle(HttpExchange ex) throws java.io.IOException {
        String path = ex.getRequestURI().getPath();
        String query = ex.getRequestURI().getQuery() == null ? "" : ex.getRequestURI().getQuery();
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(ex.getRequestMethod() + " " + path + "?" + query + " " + ex.getRequestHeaders().getFirst("Authorization")
                + " " + body);
        Object reply;
        int status = 200;
        if (path.startsWith("/gl2/")) {
            status = 401;
            reply = Map.of("message", "401 Unauthorized");
        } else {
            reply = switch (path) {
                case "/jira/rest/api/2/myself" -> Map.of("name", "me", "displayName", "Me");
                case "/jira/rest/api/2/search" -> Map.of("issues", List.of(
                        Map.of("key", "ACME-1", "fields", Map.of("summary", "Crash on save", "status", Map.of("name", "In Progress"),
                                "priority", Map.of("name", "Blocker"), "issuetype", Map.of("name", "Bug"),
                                "duedate", LocalDate.now().minusDays(1).toString(), "updated", ago(Duration.ofHours(1)),
                                "comment", Map.of("comments", List.of(
                                        Map.of("author", Map.of("name", "me"), "created", ago(Duration.ofDays(2)), "body", "looking"),
                                        Map.of("author", Map.of("name", "bob", "displayName", "Bob"), "created",
                                                ago(Duration.ofHours(1)), "body", "Can you check the logs I attached?"))))),
                        Map.of("key", "ACME-2", "fields", Map.of("summary", "Docs typo", "status", Map.of("name", "Open"),
                                "priority", Map.of("name", "Minor"), "updated", acme2Updated))));
                case "/github/graphql" -> github();
                case "/gl1/api/v4/user" -> Map.of("id", 7, "username", "me");
                case "/gl1/api/v4/merge_requests" -> query.contains("created_by_me") ? List.of(mr(11, 5, "Add cache", "me"))
                        : query.contains("reviewer_id=7") ? List.of(mr(12, 9, "Refactor pool", "dave")) : List.of();
                case "/gl1/api/v4/todos" -> List.of();
                case "/gl1/api/v4/projects/11/merge_requests/5" -> Map.of("author", Map.of("username", "me"), "has_conflicts", true,
                        "head_pipeline", Map.of("status", "failed"), "detailed_merge_status", "conflict");
                case "/gl1/api/v4/projects/11/merge_requests/5/commits" -> List.of(Map.of("committed_date", ago(Duration.ofDays(1))));
                case "/gl1/api/v4/projects/11/merge_requests/5/approvals" -> Map.of("approved", false, "approvals_left", 1,
                        "approved_by", List.of());
                case "/gl1/api/v4/projects/11/merge_requests/5/discussions" -> List.of(Map.of("notes", List.of(
                        Map.of("author", Map.of("username", "erin"), "created_at", ago(Duration.ofHours(3)), "body",
                                "Why a static cache here?", "resolvable", true, "resolved", false, "system", false))));
                case "/gl1/api/v4/projects/12/merge_requests/9" -> Map.of("author", Map.of("username", "dave"),
                        "head_pipeline", Map.of("status", "success"), "detailed_merge_status", "not_approved");
                case "/gl1/api/v4/projects/12/merge_requests/9/commits" -> List.of(Map.of("committed_date", ago(Duration.ofHours(5))));
                case "/gl1/api/v4/projects/12/merge_requests/9/approvals" -> Map.of("approved", false, "approvals_left", 1,
                        "approved_by", List.of());
                case "/gl1/api/v4/projects/12/merge_requests/9/discussions" -> List.of();
                default -> {
                    status = 404;
                    yield Map.of("error", "no " + path);
                }
            };
        }
        byte[] bytes = McpJsonDefaults.getMapper().writeValueAsString(reply).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    static Map<String, Object> mr(int project, int iid, String title, String author) {
        return Map.of("project_id", project, "iid", iid, "title", title, "web_url", "https://gl/" + project + "/" + iid,
                "references", Map.of("full", "grp/p" + project + "!" + iid), "draft", false, "created_at", ago(Duration.ofDays(3)),
                "updated_at", ago(Duration.ofHours(2)), "author", Map.of("username", author));
    }

    static Map<String, Object> pr(int n, String author, String decision, String ci, Map<String, Object> extra) {
        Map<String, Object> m = new java.util.LinkedHashMap<>(Map.of("number", n, "title", "PR " + n, "url", "https://gh/r/pull/" + n,
                "isDraft", false, "createdAt", ago(Duration.ofDays(4)), "updatedAt", ago(Duration.ofHours(2)),
                "repository", Map.of("nameWithOwner", "org/r"), "author", Map.of("login", author), "mergeable", "MERGEABLE",
                "commits", Map.of("nodes", List.of(Map.of("commit", Map.of("committedDate", ago(Duration.ofDays(1)),
                        "statusCheckRollup", Map.of("state", ci)))))));
        if (decision != null) {
            m.put("reviewDecision", decision);
        }
        m.put("reviews", Map.of("nodes", List.of()));
        m.put("reviewThreads", Map.of("nodes", List.of()));
        m.put("comments", Map.of("nodes", List.of()));
        m.put("reviewRequests", Map.of("nodes", List.of()));
        m.putAll(extra);
        return m;
    }

    private Map<String, Object> github() {
        Map<String, Object> ready = pr(1, "me", "APPROVED", "SUCCESS", Map.of());
        Map<String, Object> changes = pr(2, "me", "CHANGES_REQUESTED", "FAILURE", Map.of(
                "reviews", Map.of("nodes", List.of(Map.of("author", Map.of("login", "alice"), "state", "CHANGES_REQUESTED",
                        "submittedAt", ago(Duration.ofHours(4)), "body", "Please add a test"))),
                "reviewThreads", Map.of("nodes", List.of(Map.of("isResolved", false, "comments", Map.of("nodes", List.of(
                        Map.of("author", Map.of("login", "ci-helper[bot]"), "createdAt", ago(Duration.ofHours(3)), "body", "lint"))))))));
        Map<String, Object> review = pr(3, "carol", null, "SUCCESS", Map.of(
                "reviewRequests", Map.of("nodes", List.of(Map.of("requestedReviewer", Map.of("login", "me"))))));
        return Map.of("data", Map.of("viewer", Map.of("login", "me"),
                "authored", Map.of("nodes", List.of(ready, changes)),
                "review", Map.of("nodes", List.of(review)),
                "assigned", Map.of("nodes", List.of(ready)),
                "mentioned", Map.of("nodes", List.of())));
    }

    @Test
    void prioritizesAcrossSources() throws Exception {
        ToolHarness tools = new ToolHarness();
        Map<String, Object> m = ok(tools, "daily_status", "config", config.toString(), "table_format", "markdown");
        Map<String, Object> sources = map(m.get("sources"));
        assertEquals("ok, 2 item(s)", sources.get("jira jira"), sources.toString());
        assertEquals("ok, 3 item(s)", sources.get("github github"));
        assertEquals("ok, 2 item(s)", sources.get("gitlab work"));
        assertTrue(sources.get("gitlab other").toString().startsWith("error: GitLab other refused the credentials"));

        List<?> needs = (List<?>) m.get("needs_you");
        List<Object> ids = needs.stream().map(x -> map(x).get("id")).toList();
        // changes requested + new review + failing CI (120) outranks an overdue blocker with a new comment (110)
        assertEquals(List.of("org/r#2", "ACME-1"), ids.subList(0, 2), ids.toString());
        assertTrue(ids.containsAll(List.of("org/r#2", "org/r#3", "grp/p11!5", "grp/p12!9", "org/r#1")), ids.toString());
        Map<String, Object> acme1 = map(needs.get(1));
        assertTrue(acme1.get("why").toString().contains("overdue") && acme1.get("why").toString().contains("Blocker priority")
                && acme1.get("why").toString().contains("1 new comment from Bob"), acme1.toString());
        Map<String, Object> pr2 = needs.stream().map(x -> map(x)).filter(x -> x.get("id").equals("org/r#2")).findFirst().orElseThrow();
        assertEquals(List.of("changes requested", "1 new comment from alice", "CI failing"), pr2.get("why"));
        Map<String, Object> pr1 = needs.stream().map(x -> map(x)).filter(x -> x.get("id").equals("org/r#1")).findFirst().orElseThrow();
        assertEquals(List.of("approved, ready to merge"), pr1.get("why"));
        assertEquals("yours", pr1.get("roles"), "assigned is folded into authored");
        Map<String, Object> mr5 = needs.stream().map(x -> map(x)).filter(x -> x.get("id").equals("grp/p11!5")).findFirst().orElseThrow();
        assertTrue(mr5.get("why").toString().contains("1 new comment from erin") && mr5.get("why").toString().contains("CI failing")
                && mr5.get("why").toString().contains("merge conflicts"), mr5.toString());
        Map<String, Object> mr9 = needs.stream().map(x -> map(x)).filter(x -> x.get("id").equals("grp/p12!9")).findFirst().orElseThrow();
        assertTrue(mr9.get("why").toString().startsWith("[your review is requested"), mr9.toString());
        assertEquals("ACME-2", map(((List<?>) m.get("in_progress")).get(0)).get("id"));

        String digest = (String) m.get("digest");
        assertTrue(digest.startsWith("# Daily status"), digest);
        assertTrue(digest.contains("## Needs you (6)"), digest);
        assertTrue(digest.contains("> Bob (comment, "), digest);
        assertTrue(digest.contains("**Sources not read:** gitlab other: error"), digest);
        assertTrue(((String) m.get("table")).startsWith("| item | title | why | new |"));
        assertTrue(requests.stream().anyMatch(r -> r.startsWith("POST /github/graphql") && r.contains("Bearer gt")
                && r.contains("review-requested:@me") && r.contains("mentions:@me")), requests.toString());
        assertTrue(requests.stream().anyMatch(r -> r.contains("jql=assignee+=+currentUser()+AND+resolution+=+Unresolved")),
                requests.toString());
    }

    @Test
    void marksOnlyChangesAsNew() throws Exception {
        ToolHarness tools = new ToolHarness();
        ok(tools, "daily_status", "config", config.toString(), "sources", List.of("jira"));
        Map<String, Object> again = ok(tools, "daily_status", "config", config.toString(), "sources", List.of("jira"));
        assertTrue(((List<?>) again.get("in_progress")).stream().noneMatch(x -> map(x).containsKey("new")));
        acme2Updated = ago(Duration.ofMinutes(1));
        Map<String, Object> third = ok(tools, "daily_status", "config", config.toString(), "sources", List.of("jira"),
                "digest_only", false);
        Map<String, Object> acme2 = ((List<?>) third.get("in_progress")).stream().map(x -> map(x))
                .filter(x -> x.get("id").equals("ACME-2")).findFirst().orElseThrow();
        assertEquals(true, acme2.get("new"));
        assertTrue(Files.exists(tmp.resolve("status-state.json")));
    }

    @Test
    void notConfigured() {
        // no file and (in the build environment) no GITLAB_TOKEN
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("GITLAB_TOKEN") == null);
        assertTrue(error(new ToolHarness(), "daily_status", "config", tmp.resolve("none.json").toString(), "sources",
                List.of("gitlab")).startsWith("Nothing configured"));
    }
}
