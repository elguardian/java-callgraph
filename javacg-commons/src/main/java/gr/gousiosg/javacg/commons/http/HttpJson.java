package gr.gousiosg.javacg.commons.http;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import gr.gousiosg.javacg.commons.json.Json;

/** JSON over HTTP with an Authorization header; failures become IOExceptions with a readable message. */
public final class HttpJson {

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    /** Thrown for non-2xx answers, with the status code. */
    public static final class HttpStatusException extends IOException {
        private static final long serialVersionUID = 1L;
        private final int status;

        HttpStatusException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    private HttpJson() {
    }

    public static String bearer(String token) {
        return "Bearer " + token;
    }

    public static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    public static Object get(String url, String authorization, String what) throws IOException, InterruptedException {
        return request("GET", url, authorization, null, what);
    }

    public static Object post(String url, String authorization, Object body, String what) throws IOException, InterruptedException {
        return request("POST", url, authorization, Json.write(body), what);
    }

    /** {@code what} names the service in errors, e.g. "GitLab work". */
    public static Object request(String method, String url, String authorization, String body, String what)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json").header("User-Agent", "javacg");
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
            throw new HttpStatusException(r.statusCode(), what + " refused the credentials (HTTP " + r.statusCode() + ")");
        }
        if (r.statusCode() / 100 != 2) {
            String text = r.body() == null ? "" : r.body();
            throw new HttpStatusException(r.statusCode(), what + " failed (HTTP " + r.statusCode() + "): "
                    + (text.length() > 300 ? text.substring(0, 300) + "…" : text));
        }
        try {
            return r.body() == null || r.body().isBlank() ? null : Json.parse(r.body());
        } catch (IllegalArgumentException e) {
            throw new IOException(what + " did not answer JSON: " + e.getMessage(), e);
        }
    }
}
