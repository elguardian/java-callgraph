package io.github.elguardian.mcpsettools.devtools.logs;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns a log4j2 / logback / JBoss Logging pattern layout into a regular expression for the
 * first line of a record, with named groups {@code ts}, {@code level}, {@code thread},
 * {@code logger}, {@code message} and others ({@code class}, {@code method}, {@code line},
 * {@code mdc_<key>}, ...). Exception converters ({@code %e}, {@code %ex}, {@code %throwable})
 * produce nothing on the first line: the stack trace follows as continuation lines.
 */
final class LayoutCompiler {

    /** The compiled regex and, when the layout has a {@code %d}, its date format. */
    record Compiled(String regex, String dateFormat, String zone) {
    }

    private static final Set<String> DATE = Set.of("d", "date");
    private static final Set<String> LEVEL = Set.of("p", "le", "level");
    private static final Set<String> THREAD = Set.of("t", "thread", "tn", "threadName");
    private static final Set<String> LOGGER = Set.of("c", "lo", "logger");
    private static final Set<String> MESSAGE = Set.of("m", "msg", "message", "s");
    private static final Set<String> EXCEPTION = Set.of("e", "E", "ex", "exception", "throwable", "xEx", "xException",
            "xThrowable", "rEx", "rException", "rThrowable", "wEx", "wex", "n", "K");
    private static final Set<String> NUMBER = Set.of("r", "relative", "i", "pid", "processId", "T", "tid", "threadId",
            "sn", "sequenceNumber", "N", "nano");

    private final StringBuilder regex = new StringBuilder("^");
    private final Set<String> groups = new HashSet<>();
    private String dateFormat;
    private String zone;

    private LayoutCompiler() {
    }

    static Compiled compile(String layout) {
        LayoutCompiler c = new LayoutCompiler();
        c.append(layout);
        // trailing whitespace of padded fields and the line end
        c.regex.append("\\s*$");
        return new Compiled(c.regex.toString(), c.dateFormat, c.zone);
    }

    private void append(String layout) {
        int i = 0;
        while (i < layout.length()) {
            char ch = layout.charAt(i);
            if (ch == '%') {
                i = converter(layout, i + 1);
            } else if (ch == '$' && i + 1 < layout.length() && layout.charAt(i + 1) == '{') {
                // a property placeholder (${PID}, ${sys:x}) left unresolved in the layout
                i = close(layout, i + 1, '{', '}') + 1;
                regex.append("\\S*");
            } else if (Character.isWhitespace(ch)) {
                while (i < layout.length() && Character.isWhitespace(layout.charAt(i))) {
                    i++;
                }
                regex.append("\\s+");
            } else {
                regex.append(Pattern.quote(String.valueOf(ch)));
                i++;
            }
        }
    }

    /** Parses {@code [-][min][.max]name[(inner)]{option}...} starting after the {@code %}. */
    private int converter(String s, int i) {
        if (i >= s.length()) {
            regex.append("%");
            return i;
        }
        if (s.charAt(i) == '%') {
            regex.append("%");
            return i + 1;
        }
        int start = i;
        while (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '.' || Character.isDigit(s.charAt(i)))) {
            i++;
        }
        String modifier = s.substring(start, i);
        // %-5p, %20c, %15.15t pad with spaces; %.30c only truncates
        boolean padded = modifier.matches("-?[1-9]\\d*(\\.-?\\d+)?");
        int nameStart = i;
        while (i < s.length() && Character.isLetter(s.charAt(i))) {
            i++;
        }
        String name = s.substring(nameStart, i);
        String inner = null;
        if (i < s.length() && s.charAt(i) == '(') {
            int end = close(s, i, '(', ')');
            inner = s.substring(i + 1, end);
            i = end + 1;
        }
        List<String> options = new ArrayList<>();
        while (i < s.length() && s.charAt(i) == '{') {
            int end = close(s, i, '{', '}');
            options.add(s.substring(i + 1, end));
            i = end + 1;
        }
        if (inner == null && !options.isEmpty() && options.get(0).contains("%") && !DATE.contains(name)) {
            // log4j2 %highlight{%-5p}, %style{%d}{bold}: the first option is the pattern
            inner = options.get(0);
        }
        if (inner != null) {
            append(inner);
            return i;
        }
        String part = part(name, options);
        if (part.isEmpty()) {
            return i;
        }
        regex.append(padded ? "\\s*" + part + "\\s*" : part);
        return i;
    }

    private String part(String name, List<String> options) {
        if (DATE.contains(name)) {
            String fmt = options.isEmpty() ? "DEFAULT" : options.get(0);
            if (options.size() > 1) {
                zone = options.get(1);
            }
            String pattern = DateFormats.named(fmt);
            if (dateFormat == null) {
                dateFormat = pattern;
                return group("ts", DateFormats.regex(pattern));
            }
            return "(?:" + DateFormats.regex(pattern) + ")";
        }
        if (LEVEL.contains(name)) {
            return group("level", "[A-Za-z]+");
        }
        if (THREAD.contains(name)) {
            return group("thread", ".+?");
        }
        if (LOGGER.contains(name)) {
            return group("logger", "\\S+?");
        }
        if (MESSAGE.contains(name)) {
            return group("message", ".*?");
        }
        if (EXCEPTION.contains(name)) {
            return "";
        }
        if (NUMBER.contains(name)) {
            return "\\d+";
        }
        return switch (name) {
            case "C", "class" -> group("class", "\\S+?");
            case "M", "method" -> group("method", "\\S+?");
            case "L", "line" -> group("line", "\\d+|\\?");
            case "F", "file" -> group("file", "\\S+?");
            case "h", "H", "host", "hostName" -> group("host", "\\S+");
            case "X", "mdc", "MDC" -> options.isEmpty() ? ".*?"
                    : group("mdc_" + options.get(0).replaceAll("[^A-Za-z0-9]", "_").toLowerCase(Locale.ROOT), ".*?");
            default -> ".*?";
        };
    }

    private String group(String name, String body) {
        String safe = name.replaceAll("[^A-Za-z0-9]", "");
        if (groups.add(safe)) {
            return "(?<" + safe + ">" + body + ")";
        }
        return "(?:" + body + ")";
    }

    /** Index of the bracket closing the one at {@code open}, honoring nesting. */
    private static int close(String s, int open, char o, char c) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\') {
                i++;
            } else if (ch == o) {
                depth++;
            } else if (ch == c && --depth == 0) {
                return i;
            }
        }
        throw new IllegalArgumentException("Unbalanced '" + o + "' in log pattern: " + s);
    }
}
