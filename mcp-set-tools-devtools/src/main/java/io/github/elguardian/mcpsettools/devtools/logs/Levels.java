package io.github.elguardian.mcpsettools.devtools.logs;

import java.util.List;
import java.util.Locale;

/** Level names of log4j, logback, JUL, JBoss and Maven mapped to one scale. */
public final class Levels {

    public static final List<String> ORDER = List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL");

    private Levels() {
    }

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return switch (raw.strip().toUpperCase(Locale.ROOT)) {
            case "TRACE", "FINEST", "FINER", "ALL" -> "TRACE";
            case "DEBUG", "FINE", "CONFIG", "VERBOSE" -> "DEBUG";
            case "INFO", "INFORMATION", "NOTICE" -> "INFO";
            case "WARN", "WARNING" -> "WARN";
            case "ERROR", "SEVERE", "ERR" -> "ERROR";
            case "FATAL", "CRITICAL", "CRIT", "EMERG", "ALERT" -> "FATAL";
            default -> raw.strip().toUpperCase(Locale.ROOT);
        };
    }

    public static int rank(String normalized) {
        return normalized == null ? -1 : ORDER.indexOf(normalized);
    }
}
