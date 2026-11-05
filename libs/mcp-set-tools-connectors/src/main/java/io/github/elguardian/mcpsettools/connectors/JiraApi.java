package io.github.elguardian.mcpsettools.connectors;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import io.github.elguardian.mcpsettools.commons.http.HttpJson;

/**
 * Jira REST v2: Data Center/Server with a personal access token (Bearer), Jira Cloud with user
 * and API token (Basic). Search falls back to Cloud's {@code /search/jql} when {@code /search} is gone.
 */
public final class JiraApi {

    private final String baseUrl;
    private final String authorization;
    private final String name;

    /** {@code user} null: Bearer token (Data Center); else Basic user:token (Cloud). */
    public JiraApi(String baseUrl, String token, String user, String name) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.authorization = user == null ? HttpJson.bearer(token) : HttpJson.basic(user, token);
        this.name = name == null ? "Jira" : "Jira " + name;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String browseUrl(String key) {
        return baseUrl + "/browse/" + key;
    }

    /** The authenticated user ({@code name}, {@code key}, {@code accountId}, {@code displayName}...). */
    public Object myself() throws IOException, InterruptedException {
        return HttpJson.get(baseUrl + "/rest/api/2/myself", authorization, name);
    }

    /** The search response ({@code issues} with the requested {@code fields}). */
    public Object search(String jql, int max, String fields) throws IOException, InterruptedException {
        String query = "?jql=" + URLEncoder.encode(jql, StandardCharsets.UTF_8) + "&maxResults=" + max + "&fields=" + fields;
        try {
            return HttpJson.get(baseUrl + "/rest/api/2/search" + query, authorization, name);
        } catch (HttpJson.HttpStatusException e) {
            if (e.status() != 404 && e.status() != 410) {
                throw e;
            }
            return HttpJson.get(baseUrl + "/rest/api/2/search/jql" + query, authorization, name);
        }
    }
}
