package io.github.elguardian.mcpsettools.mcp;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

import io.github.elguardian.mcpsettools.commons.json.Json;

/** JSON navigation and HTTP helpers for the status sources. */
final class StatusJson {

    private static final DateTimeFormatter JIRA_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");

    private StatusJson() {
    }

    /** The value at a path of keys, or null. */
    static Object at(Object root, String... path) {
        return Json.at(root, path);
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

    static Object parse(String json) throws IOException {
        try {
            return Json.parse(json);
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage(), e);
        }
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
