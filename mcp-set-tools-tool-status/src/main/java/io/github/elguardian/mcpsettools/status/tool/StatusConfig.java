package io.github.elguardian.mcpsettools.status.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Where to look for pending work: {@code ~/.mcp-set-tools/status.json} (or {@code $MCPSETTOOLS_STATUS_CONFIG}),
 * else the environment ({@code JIRA_URL}/{@code JIRA_TOKEN}, {@code GITHUB_TOKEN},
 * {@code GITLAB_URL}/{@code GITLAB_TOKEN}). Tokens are named by environment variable, never stored.
 */
final class StatusConfig {

    record Instance(String kind, String name, String url, String tokenEnv, String userEnv, String jql, List<String> ignore) {
        String token() {
            return StatusJson.secret(tokenEnv);
        }

        String user() {
            return StatusJson.secret(userEnv);
        }
    }

    final Path file;
    final List<Instance> instances = new ArrayList<>();

    private StatusConfig(Path file) {
        this.file = file;
    }

    static Path defaultFile() {
        String env = StatusJson.secret("MCPSETTOOLS_STATUS_CONFIG");
        return env != null ? Path.of(env) : Path.of(System.getProperty("user.home"), ".mcp-set-tools", "status.json");
    }

    static StatusConfig load(Path file) throws IOException {
        StatusConfig c = new StatusConfig(file);
        if (Files.isRegularFile(file)) {
            Object root = StatusJson.parse(Files.readString(file));
            for (String kind : List.of("jira", "github", "gitlab")) {
                for (Object o : StatusJson.list(root, kind)) {
                    String url = StatusJson.str(o, kind.equals("github") ? "api_url" : "url");
                    if (url == null) {
                        url = kind.equals("github") ? "https://api.github.com" : kind.equals("gitlab") ? "https://gitlab.com" : null;
                    }
                    if (url == null) {
                        throw new IOException(file + ": a jira entry needs \"url\"");
                    }
                    String token = StatusJson.str(o, "token_env");
                    if (token == null) {
                        throw new IOException(file + ": " + kind + " " + url + " needs \"token_env\" (the name of the "
                                + "environment variable holding the token)");
                    }
                    List<String> ignore = new ArrayList<>();
                    StatusJson.list(o, "ignore_repos").forEach(x -> ignore.add(x.toString()));
                    c.instances.add(new Instance(kind, StatusJson.str(o, "name") != null ? StatusJson.str(o, "name")
                            : host(url), url.replaceAll("/+$", ""), token, StatusJson.str(o, "user_env"), StatusJson.str(o, "jql"),
                            ignore));
                }
            }
            return c;
        }
        String jira = StatusJson.secret("JIRA_URL");
        if (jira != null && StatusJson.secret("JIRA_TOKEN") != null) {
            c.instances.add(new Instance("jira", host(jira), jira.replaceAll("/+$", ""), "JIRA_TOKEN", "JIRA_USER", null, List.of()));
        }
        String gh = StatusJson.secret("GITHUB_TOKEN") != null ? "GITHUB_TOKEN" : StatusJson.secret("GH_TOKEN") != null ? "GH_TOKEN" : null;
        if (gh != null) {
            c.instances.add(new Instance("github", "github.com", "https://api.github.com", gh, null, null, List.of()));
        }
        if (StatusJson.secret("GITLAB_TOKEN") != null) {
            String url = StatusJson.secret("GITLAB_URL") != null ? StatusJson.secret("GITLAB_URL") : "https://gitlab.com";
            c.instances.add(new Instance("gitlab", host(url), url.replaceAll("/+$", ""), "GITLAB_TOKEN", null, null, List.of()));
        }
        return c;
    }

    static String host(String url) {
        return url.replaceFirst("^https?://", "").replaceFirst("/.*$", "");
    }

    /** A config file example for the "not configured" message. */
    static final String EXAMPLE = """
            {"jira": [{"url": "https://issues.redhat.com", "token_env": "JIRA_TOKEN"}],
             "github": [{"api_url": "https://api.github.com", "token_env": "GITHUB_TOKEN"}],
             "gitlab": [{"url": "https://gitlab.com", "token_env": "GITLAB_TOKEN"},
                        {"url": "https://gitlab.example.com", "token_env": "GITLAB_WORK_TOKEN"}]}""";

    static Map<String, Object> describe(Instance i) {
        return Map.of("kind", i.kind(), "name", i.name(), "url", i.url(), "token_env", i.tokenEnv());
    }
}
