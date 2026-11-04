package gr.gousiosg.javacg.mcp;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.modelcontextprotocol.json.McpJsonDefaults;

/**
 * Searches Jira through its REST API: Jira Data Center/Server ({@code /rest/api/2/search}, a
 * personal access token as Bearer) and Jira Cloud ({@code /rest/api/2/search/jql}, user and API
 * token as Basic). Credentials come from the environment only, never from tool arguments.
 */
final class JiraClient {

    /** {@code JIRA_URL}, {@code JIRA_TOKEN}, optional {@code JIRA_USER} (system properties javacg.jira.* override, for tests). */
    record Config(String baseUrl, String token, String user) {

        static Optional<Config> fromEnvironment() {
            String url = value("javacg.jira.url", "JIRA_URL");
            String token = value("javacg.jira.token", "JIRA_TOKEN");
            if (url == null || token == null) {
                return Optional.empty();
            }
            return Optional.of(new Config(url.replaceAll("/+$", ""), token, value("javacg.jira.user", "JIRA_USER")));
        }

        private static String value(String property, String env) {
            String v = System.getProperty(property);
            if (v == null || v.isBlank()) {
                v = System.getenv(env);
            }
            return v == null || v.isBlank() ? null : v.strip();
        }
    }

    record Issue(String key, String summary, String status, List<String> fixVersions, List<String> versions, String text,
            String url) {
    }

    private static final String FIELDS = "summary,status,fixVersions,versions,description,comment";

    private final Config config;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    JiraClient(Config config) {
        this.config = config;
    }

    String baseUrl() {
        return config.baseUrl();
    }

    List<Issue> search(String jql, int max) throws IOException, InterruptedException {
        String query = "?jql=" + URLEncoder.encode(jql, StandardCharsets.UTF_8) + "&maxResults=" + max + "&fields=" + FIELDS;
        HttpResponse<String> r = get("/rest/api/2/search" + query);
        if (r.statusCode() == 404 || r.statusCode() == 410) {
            // Jira Cloud replaced /search with /search/jql
            r = get("/rest/api/2/search/jql" + query);
        }
        if (r.statusCode() == 401 || r.statusCode() == 403) {
            throw new IOException("Jira refused the credentials (HTTP " + r.statusCode() + "): check JIRA_TOKEN"
                    + (config.user() == null ? " (Jira Cloud also needs JIRA_USER)" : ""));
        }
        if (r.statusCode() / 100 != 2) {
            throw new IOException("Jira search failed (HTTP " + r.statusCode() + "): " + abbreviate(r.body()));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> body = McpJsonDefaults.getMapper().readValue(r.body(), Map.class);
        List<Issue> issues = new ArrayList<>();
        for (Object o : list(body.get("issues"))) {
            Map<?, ?> issue = (Map<?, ?>) o;
            Map<?, ?> fields = issue.get("fields") instanceof Map<?, ?> f ? f : Map.of();
            StringBuilder text = new StringBuilder();
            text(fields.get("description"), text);
            if (fields.get("comment") instanceof Map<?, ?> c) {
                for (Object comment : list(c.get("comments"))) {
                    if (comment instanceof Map<?, ?> cm) {
                        text.append('\n');
                        text(cm.get("body"), text);
                    }
                }
            }
            String key = String.valueOf(issue.get("key"));
            issues.add(new Issue(key, str(fields.get("summary")), fields.get("status") instanceof Map<?, ?> s ? str(s.get("name"))
                    : null, names(fields.get("fixVersions")), names(fields.get("versions")), text.toString(),
                    config.baseUrl() + "/browse/" + key));
        }
        return issues;
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(config.baseUrl() + path)).timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json").GET();
        if (config.user() != null) {
            b.header("Authorization", "Basic " + Base64.getEncoder().encodeToString((config.user() + ":" + config.token())
                    .getBytes(StandardCharsets.UTF_8)));
        } else {
            b.header("Authorization", "Bearer " + config.token());
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** Wiki-markup strings as they are; Atlassian document format (Cloud v3) flattened to its text nodes. */
    private static void text(Object v, StringBuilder into) {
        if (v instanceof String s) {
            into.append(s);
        } else if (v instanceof Map<?, ?> m) {
            if (m.get("text") instanceof String t) {
                into.append(t);
            }
            for (Object c : list(m.get("content"))) {
                text(c, into);
            }
            if ("paragraph".equals(m.get("type")) || "codeBlock".equals(m.get("type")) || "hardBreak".equals(m.get("type"))) {
                into.append('\n');
            }
        }
    }

    private static List<?> list(Object o) {
        return o instanceof List<?> l ? l : List.of();
    }

    private static List<String> names(Object o) {
        List<String> names = new ArrayList<>();
        for (Object v : list(o)) {
            if (v instanceof Map<?, ?> m && m.get("name") != null) {
                names.add(m.get("name").toString());
            }
        }
        return names;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static String abbreviate(String s) {
        return s.length() <= 300 ? s : s.substring(0, 300) + "…";
    }
}
