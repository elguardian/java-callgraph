package io.github.elguardian.mcpsettools.devtools.logs;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalQueries;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToLongFunction;
import java.util.regex.Pattern;

/**
 * Log timestamp formats: the named formats of log4j2/logback, SimpleDateFormat-style patterns
 * turned into a regex and a parser to epoch microseconds. Time-only formats get the date
 * 1970-01-01, so their records order by time of day.
 */
final class DateFormats {

    static final long NONE = Long.MIN_VALUE;

    private static final Map<String, String> NAMED = Map.of(
            "DEFAULT", "yyyy-MM-dd HH:mm:ss,SSS",
            "ISO8601", "yyyy-MM-dd['T'][' ']HH:mm:ss[,SSS][.SSS]",
            "ISO8601_BASIC", "yyyyMMdd'T'HHmmss,SSS",
            "ABSOLUTE", "HH:mm:ss,SSS",
            "DATE", "dd MMM yyyy HH:mm:ss,SSS",
            "COMPACT", "yyyyMMddHHmmssSSS",
            "UNIX", "UNIX",
            "UNIX_MILLIS", "UNIX_MILLIS");

    private DateFormats() {
    }

    static String named(String fmt) {
        return NAMED.getOrDefault(fmt.strip(), fmt.strip());
    }

    /** A regex matching timestamps of the pattern (no groups). */
    static String regex(String pattern) {
        if (pattern.equals("UNIX") || pattern.equals("UNIX_MILLIS")) {
            return "\\d+";
        }
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < pattern.length()) {
            char c = pattern.charAt(i);
            if (c == '\'') {
                int end = pattern.indexOf('\'', i + 1);
                if (end == i + 1) {
                    sb.append("'");
                    i += 2;
                    continue;
                }
                end = end < 0 ? pattern.length() : end;
                sb.append(Pattern.quote(pattern.substring(i + 1, end)));
                i = end + 1;
            } else if (c == '[') {
                sb.append("(?:");
                i++;
            } else if (c == ']') {
                sb.append(")?");
                i++;
            } else if (Character.isLetter(c)) {
                int n = 1;
                while (i + n < pattern.length() && pattern.charAt(i + n) == c) {
                    n++;
                }
                sb.append(field(c, n));
                i += n;
            } else {
                sb.append(Pattern.quote(String.valueOf(c)));
                i++;
            }
        }
        return sb.toString();
    }

    private static String field(char c, int n) {
        return switch (c) {
            case 'y', 'u', 'Y' -> n == 2 ? "\\d{2}" : "\\d{4}";
            case 'M', 'L' -> n == 1 ? "\\d{1,2}" : n == 2 ? "\\d{2}" : n == 3 ? "[A-Za-z]{3}\\.?" : "[A-Za-z]+";
            case 'd', 'H', 'h', 'k', 'K', 'm', 's' -> n == 1 ? "\\d{1,2}" : "\\d{" + n + "}";
            case 'S' -> "\\d{" + n + "}";
            case 'n' -> "\\d+";
            case 'D' -> "\\d{1,3}";
            case 'E' -> "[A-Za-z]+";
            case 'a' -> "[AaPp][Mm]";
            case 'Z' -> "(?:[+-]\\d{4}|Z)";
            case 'X', 'x' -> "(?:Z|[+-]\\d{2}(?::?\\d{2})?)";
            case 'z', 'V', 'O' -> "[A-Za-z0-9/_+:-]+";
            default -> "\\S+?";
        };
    }

    /** A parser from timestamp text to epoch microseconds ({@link #NONE} when it does not parse). */
    static ToLongFunction<String> parser(String pattern, String zone) {
        if (pattern.equals("UNIX")) {
            return s -> safeLong(s, 1_000_000);
        }
        if (pattern.equals("UNIX_MILLIS")) {
            return s -> safeLong(s, 1_000);
        }
        DateTimeFormatter f = new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(pattern)
                .toFormatter(Locale.ROOT);
        ZoneId fixed = zone == null || zone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(zone);
        return s -> {
            try {
                return toMicros(f.parse(s.strip()), fixed);
            } catch (DateTimeException e) {
                return NONE;
            }
        };
    }

    static long toMicros(TemporalAccessor t, ZoneId defaultZone) {
        LocalDate date = t.query(TemporalQueries.localDate());
        if (date == null) {
            int month = t.isSupported(ChronoField.MONTH_OF_YEAR) ? t.get(ChronoField.MONTH_OF_YEAR) : 1;
            int day = t.isSupported(ChronoField.DAY_OF_MONTH) ? t.get(ChronoField.DAY_OF_MONTH) : 1;
            date = LocalDate.of(1970, month, day);
        }
        LocalTime time = t.query(TemporalQueries.localTime());
        if (time == null) {
            time = LocalTime.MIDNIGHT;
        }
        ZoneId z = t.query(TemporalQueries.zone());
        var instant = date.atTime(time).atZone(z == null ? defaultZone : z).toInstant();
        return instant.getEpochSecond() * 1_000_000 + instant.getNano() / 1_000;
    }

    private static long safeLong(String s, long scale) {
        try {
            return Long.parseLong(s.strip()) * scale;
        } catch (NumberFormatException e) {
            return NONE;
        }
    }
}
