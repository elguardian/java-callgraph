package gr.gousiosg.javacg.mcp;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.json.McpJsonDefaults;

/** JSON navigation and HTTP helpers for the status sources. */
final class StatusJson {

    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private static final DateTimeFormatter JIRA_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");

    private StatusJson() {
    }

    /** The value at a path of keys, or null. */
    static Object at(Object root, String... path) {
        Object o = root;
        for (String k : path) {
            if (!(o instanceof Map<?, ?> m)) {
                return null;
            }
            o = m.get(k);
        }
        return o;
    }

    static String str(Object root, String... path) {
        Object v = at(root, path);
        return v == null ? null : v.toString();
    }

    static List<?> list(Object root, String... path) {
        return at(root, path) instanceof List<?> l ? l : List.of();
    }

    static boolean bool(Object root, String... path) {
        return Boolean.TRUE.equals(at(root, path));
    }

    /** ISO-8601 with offset, or Jira's {@code 2024-05-01T10:00:00.000+0200}. */
    static Instant time(Object v) {
        if (v == null) {
            return null;
        }
        String s = v.toString();
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (DateTimeParseException e) {
            try {
                return OffsetDateTime.parse(s, JIRA_TIME).toInstant();
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }

    static LocalDate date(Object v) {
        if (v == null) {
            return null;
        }
        try {
            return LocalDate.parse(v.toString().substring(0, Math.min(10, v.toString().length())));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    static Instant max(Instant a, Instant b) {
        return a == null ? b : b == null ? a : a.isAfter(b) ? a : b;
    }

    /** First line of a comment, markup trimmed, at most 200 characters. */
    static String excerpt(String body) {
        if (body == null) {
            return "";
        }
        String s = body.replaceAll("(?s)```.*?```", "[code]").replaceAll("\\{code[^}]*}.*?\\{code}", "[code]")
                .replaceAll("\\s+", " ").strip();
        return s.length() <= 200 ? s : s.substring(0, 199) + "…";
    }

    @SuppressWarnings("unchecked")
    static Object parse(String json) throws IOException {
        String s = json.strip();
        return s.startsWith("[") ? McpJsonDefaults.getMapper().readValue(s, List.class)
                : McpJsonDefaults.getMapper().readValue(s, Map.class);
    }

    /** GET or POST JSON with an Authorization header; refused credentials and other failures become IOExceptions. */
    static Object request(String method, String url, String authorization, String body, String what)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json").header("User-Agent", "javacg-mcp");
        if (authorization != null) {
            b.header("Authorization", authorization);
        }
        if (body != null) {
            b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (r.statusCode() == 401 || r.statusCode() == 403) {
            throw new IOException(what + " refused the credentials (HTTP " + r.statusCode() + ")");
        }
        if (r.statusCode() / 100 != 2) {
            String text = r.body() == null ? "" : r.body();
            throw new IOException(what + " failed (HTTP " + r.statusCode() + "): "
                    + (text.length() > 300 ? text.substring(0, 300) + "…" : text));
        }
        return parse(r.body());
    }

    /** An environment variable, else a system property of the same name. */
    static String secret(String name) {
        if (name == null) {
            return null;
        }
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            v = System.getProperty(name);
        }
        return v == null || v.isBlank() ? null : v.strip();
    }
}
