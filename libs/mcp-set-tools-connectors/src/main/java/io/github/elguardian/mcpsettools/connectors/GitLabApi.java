package io.github.elguardian.mcpsettools.connectors;

import java.io.IOException;

import io.github.elguardian.mcpsettools.commons.http.HttpJson;

/** GitLab REST v4 of one instance, with a personal access token ({@code read_api}). */
public final class GitLabApi {

    private final String api;
    private final String authorization;
    private final String name;

    public GitLabApi(String url, String token, String name) {
        this.api = url.replaceAll("/+$", "") + "/api/v4";
        this.authorization = HttpJson.bearer(token);
        this.name = name == null ? "GitLab" : "GitLab " + name;
    }

    /** GET {@code /api/v4<path>}, e.g. {@code /user} or {@code /merge_requests?state=opened}. */
    public Object get(String path) throws IOException, InterruptedException {
        return HttpJson.get(api + path, authorization, name);
    }
}
