package gr.gousiosg.javacg.commons.table;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A table of text cells rendered for a destination: a terminal, Markdown, Jira wiki markup,
 * Slack, CSV/TSV or HTML. Numeric columns are right-aligned unless an alignment is set.
 */
public final class Table {

    public enum Align {
        LEFT, RIGHT, CENTER
    }

    public enum Format {
        /** Box drawn with {@code +-|}, for terminals and plain text. */
        ASCII,
        /** GitHub-flavored Markdown. */
        MARKDOWN,
        /** Jira/Confluence wiki markup ({@code ||header||} and {@code |cell|}). */
        JIRA,
        /** An ASCII table in a code block: Slack mrkdwn has no tables. */
        SLACK,
        CSV,
        TSV,
        HTML;

        public static Format parse(String s) {
            return valueOf(s.strip().toUpperCase(Locale.ROOT));
        }
    }

    private static final Pattern NUMBER = Pattern.compile(
            "[-+]?(\\d{1,3}(,\\d{3})+|\\d+)(\\.\\d+)?\\s*(%|ms|s|m|h|µs|us|ns|kb|mb|gb|x)?", Pattern.CASE_INSENSITIVE);

    private final List<String> columns;
    private final Align[] aligns;
    private final List<List<String>> rows = new ArrayList<>();
    private String title;
    private int maxWidth;

    private Table(List<String> columns) {
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("A table needs at least one column");
        }
        this.columns = List.copyOf(columns);
        this.aligns = new Align[columns.size()];
    }

    public static Table of(List<String> columns) {
        return new Table(columns);
    }

    public static Table of(String... columns) {
        return new Table(List.of(columns));
    }

    /**
     * A table from row objects; {@code columns} null or empty takes every key in first-seen order.
     */
    public static Table fromMaps(List<? extends Map<String, ?>> rows, List<String> columns) {
        List<String> cols = columns;
        if (cols == null || cols.isEmpty()) {
            Set<String> keys = new LinkedHashSet<>();
            rows.forEach(r -> keys.addAll(r.keySet()));
            cols = new ArrayList<>(keys);
        }
        Table t = new Table(cols);
        for (Map<String, ?> r : rows) {
            List<Object> cells = new ArrayList<>();
            for (String c : cols) {
                cells.add(r.get(c));
            }
            t.row(cells);
        }
        return t;
    }

    /** Parses CSV (RFC 4180) or TSV text; the first record is the header. */
    public static Table fromCsv(String text, char separator) {
        List<List<String>> records = Csv.parse(text, separator);
        if (records.isEmpty()) {
            throw new IllegalArgumentException("No CSV header");
        }
        Table t = new Table(records.get(0));
        records.subList(1, records.size()).forEach(t::row);
        return t;
    }

    public Table row(Collection<?> cells) {
        List<String> r = new ArrayList<>(columns.size());
        for (Object c : cells) {
            r.add(text(c));
        }
        while (r.size() < columns.size()) {
            r.add("");
        }
        if (r.size() > columns.size()) {
            throw new IllegalArgumentException("Row has " + r.size() + " cells, the table " + columns.size()
                    + " columns: " + cells);
        }
        rows.add(r);
        return this;
    }

    public Table row(Object... cells) {
        return row(java.util.Arrays.asList(cells));
    }

    public Table align(int column, Align align) {
        aligns[column] = align;
        return this;
    }

    public Table align(String column, Align align) {
        int i = columns.indexOf(column);
        if (i < 0) {
            throw new IllegalArgumentException("No column " + column + " in " + columns);
        }
        return align(i, align);
    }

    public Table title(String title) {
        this.title = title;
        return this;
    }

    /** Longest cell, in characters; longer cells are cut with an ellipsis. 0: no limit. */
    public Table maxWidth(int maxWidth) {
        this.maxWidth = maxWidth;
        return this;
    }

    public List<String> columns() {
        return columns;
    }

    public int size() {
        return rows.size();
    }

    public String render(Format format) {
        return switch (format) {
            case ASCII -> ascii(true);
            case SLACK -> (title == null ? "" : "*" + title + "*\n") + "```\n" + ascii(false) + "```\n";
            case MARKDOWN -> markdown();
            case JIRA -> jira();
            case CSV -> separated(',');
            case TSV -> separated('\t');
            case HTML -> html();
        };
    }

    @Override
    public String toString() {
        return render(Format.ASCII);
    }

    // ---- renderers

    private String ascii(boolean withTitle) {
        List<String> header = cut(columns);
        List<List<String>> body = rows.stream().map(this::cut).toList();
        int[] w = widths(header, body);
        StringBuilder sb = new StringBuilder();
        if (withTitle && title != null) {
            sb.append(title).append('\n');
        }
        String rule = rule(w);
        sb.append(rule);
        line(sb, header, w, true);
        sb.append(rule);
        body.forEach(r -> line(sb, r, w, false));
        if (!body.isEmpty()) {
            sb.append(rule);
        }
        return sb.toString();
    }

    private static String rule(int[] w) {
        StringBuilder sb = new StringBuilder("+");
        for (int x : w) {
            sb.append("-".repeat(x + 2)).append('+');
        }
        return sb.append('\n').toString();
    }

    private void line(StringBuilder sb, List<String> cells, int[] w, boolean header) {
        sb.append('|');
        for (int i = 0; i < w.length; i++) {
            sb.append(' ').append(pad(cells.get(i), w[i], header ? Align.LEFT : align(i))).append(" |");
        }
        sb.append('\n');
    }

    private String markdown() {
        StringBuilder sb = new StringBuilder();
        if (title != null) {
            sb.append("**").append(title).append("**\n\n");
        }
        sb.append('|');
        cut(columns).forEach(c -> sb.append(' ').append(escape(c, "|")).append(" |"));
        sb.append("\n|");
        for (int i = 0; i < columns.size(); i++) {
            sb.append(switch (align(i)) {
                case LEFT -> " --- |";
                case RIGHT -> " ---: |";
                case CENTER -> " :---: |";
            });
        }
        sb.append('\n');
        for (List<String> r : rows) {
            sb.append('|');
            cut(r).forEach(c -> sb.append(' ').append(escape(c, "|")).append(" |"));
            sb.append('\n');
        }
        return sb.toString();
    }

    private String jira() {
        StringBuilder sb = new StringBuilder();
        if (title != null) {
            sb.append('*').append(title).append("*\n");
        }
        sb.append("||");
        cut(columns).forEach(c -> sb.append(jiraCell(c)).append("||"));
        sb.append('\n');
        for (List<String> r : rows) {
            sb.append('|');
            cut(r).forEach(c -> sb.append(jiraCell(c)).append('|'));
            sb.append('\n');
        }
        return sb.toString();
    }

    /** Jira collapses empty cells and reads {@code |}, {@code {} and {@code [} as markup. */
    private static String jiraCell(String c) {
        return c.isEmpty() ? " " : escape(c, "|{}[]");
    }

    private String separated(char sep) {
        StringBuilder sb = new StringBuilder();
        Csv.write(sb, columns, sep);
        rows.forEach(r -> Csv.write(sb, r, sep));
        return sb.toString();
    }

    private String html() {
        StringBuilder sb = new StringBuilder("<table>\n");
        if (title != null) {
            sb.append("  <caption>").append(html(title)).append("</caption>\n");
        }
        sb.append("  <thead><tr>");
        cut(columns).forEach(c -> sb.append("<th>").append(html(c)).append("</th>"));
        sb.append("</tr></thead>\n  <tbody>\n");
        for (List<String> r : rows) {
            sb.append("    <tr>");
            List<String> cells = cut(r);
            for (int i = 0; i < cells.size(); i++) {
                Align a = align(i);
                sb.append(a == Align.LEFT ? "<td>" : "<td style=\"text-align:" + a.name().toLowerCase(Locale.ROOT) + "\">")
                        .append(html(cells.get(i))).append("</td>");
            }
            sb.append("</tr>\n");
        }
        return sb.append("  </tbody>\n</table>\n").toString();
    }

    // ---- helpers

    private Align align(int column) {
        if (aligns[column] != null) {
            return aligns[column];
        }
        boolean any = false;
        for (List<String> r : rows) {
            String c = r.get(column);
            if (c.isEmpty()) {
                continue;
            }
            if (!NUMBER.matcher(c).matches()) {
                return Align.LEFT;
            }
            any = true;
        }
        return any ? Align.RIGHT : Align.LEFT;
    }

    private List<String> cut(List<String> cells) {
        if (maxWidth <= 0) {
            return cells;
        }
        return cells.stream().map(c -> width(c) <= maxWidth ? c
                : c.substring(0, c.offsetByCodePoints(0, Math.max(1, maxWidth - 1))) + "…").toList();
    }

    private int[] widths(List<String> header, List<List<String>> body) {
        int[] w = new int[columns.size()];
        for (int i = 0; i < w.length; i++) {
            w[i] = width(header.get(i));
            for (List<String> r : body) {
                w[i] = Math.max(w[i], width(r.get(i)));
            }
        }
        return w;
    }

    static int width(String s) {
        return s.codePointCount(0, s.length());
    }

    private static String pad(String s, int width, Align align) {
        int gap = width - width(s);
        return switch (align) {
            case LEFT -> s + " ".repeat(gap);
            case RIGHT -> " ".repeat(gap) + s;
            case CENTER -> " ".repeat(gap / 2) + s + " ".repeat(gap - gap / 2);
        };
    }

    private static String text(Object o) {
        if (o == null) {
            return "";
        }
        String s = o instanceof Double d && d == Math.rint(d) && !d.isInfinite() && Math.abs(d) < 1e15
                ? Long.toString(d.longValue()) : o.toString();
        return s.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
    }

    private static String escape(String s, String chars) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (chars.indexOf(c) >= 0) {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static String html(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
