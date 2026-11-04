package io.github.elguardian.mcpsettools.connectors;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import io.github.elguardian.mcpsettools.commons.http.HttpJson;
import io.github.elguardian.mcpsettools.commons.json.Json;

/** GitHub GraphQL: {@code api.github.com/graphql}, or {@code https://host/api/graphql} for Enterprise ({@code /api/v3}). */
public final class GitHubApi {

    private final String graphql;
    private final String authorization;
    private final String name;

    public GitHubApi(String apiUrl, String token, String name) {
        String api = apiUrl.replaceAll("/+$", "");
        this.graphql = api.endsWith("/api/v3") ? api.replaceFirst("/api/v3$", "/api/graphql") : api + "/graphql";
        this.authorization = HttpJson.bearer(token);
        this.name = name == null ? "GitHub" : "GitHub " + name;
    }

    /** The {@code data} of a query; GraphQL errors become IOExceptions. */
    public Object query(String query, Map<String, Object> variables) throws IOException, InterruptedException {
        Object r = HttpJson.post(graphql, authorization, Map.of("query", query, "variables", variables), name);
        List<?> errors = Json.list(r, "errors");
        if (!errors.isEmpty()) {
            throw new IOException(name + ": " + Json.str(errors.get(0), "message"));
        }
        return Json.at(r, "data");
    }
}
