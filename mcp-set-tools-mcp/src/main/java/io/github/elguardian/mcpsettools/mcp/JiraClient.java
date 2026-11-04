package io.github.elguardian.mcpsettools.mcp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.elguardian.mcpsettools.commons.http.HttpJson;
import io.github.elguardian.mcpsettools.commons.json.Json;
import io.github.elguardian.mcpsettools.connectors.JiraApi;

/**
 * Searches Jira through its REST API: Jira Data Center/Server ({@code /rest/api/2/search}, a
 * personal access token as Bearer) and Jira Cloud ({@code /rest/api/2/search/jql}, user and API
 * token as Basic). Credentials come from the environment only, never from tool arguments.
 */
final class JiraClient {

    /** {@code JIRA_URL}, {@code JIRA_TOKEN}, optional {@code JIRA_USER} (system properties mcpsettools.jira.* override, for tests). */
    record Config(String baseUrl, String token, String user) {

        static Optional<Config> fromEnvironment() {
            String url = value("mcpsettools.jira.url", "JIRA_URL");
            String token = value("mcpsettools.jira.token", "JIRA_TOKEN");
            if (url == null || token == null) {
                return Optional.empty();
            }
            return Optional.of(new Config(url.replaceAll("/+$", ""), token, value("mcpsettools.jira.user", "JIRA_USER")));
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
    private final JiraApi api;

    JiraClient(Config config) {
        this.config = config;
        this.api = new JiraApi(config.baseUrl(), config.token(), config.user(), null);
    }

    String baseUrl() {
        return config.baseUrl();
    }

    List<Issue> search(String jql, int max) throws IOException, InterruptedException {
        Object body;
        try {
            body = api.search(jql, max, FIELDS);
        } catch (HttpJson.HttpStatusException e) {
            throw e.status() == 401 || e.status() == 403 ? new IOException(e.getMessage() + ": check JIRA_TOKEN"
                    + (config.user() == null ? " (Jira Cloud also needs JIRA_USER)" : ""), e) : e;
        }
        List<Issue> issues = new ArrayList<>();
        for (Object o : list(Json.at(body, "issues"))) {
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
                    : null, names(fields.get("fixVersions")), names(fields.get("versions")), text.toString(), api.browseUrl(key)));
        }
        return issues;
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
}
