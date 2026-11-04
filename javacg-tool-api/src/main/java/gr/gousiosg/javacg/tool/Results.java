package gr.gousiosg.javacg.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import gr.gousiosg.javacg.commons.table.Table;

/** Shapes tool results: pages of long lists, tables for the {@code table_format} argument. */
public final class Results {

    private Results() {
    }

    public static String lower(Enum<?> e) {
        return e.name().toLowerCase(Locale.ROOT);
    }

    /** The first {@code limit} items as {@code {total, items, truncated?}}; negative limit: all. */
    public static Map<String, Object> page(List<?> items, int limit) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", items.size());
        int n = limit < 0 ? items.size() : Math.min(limit, items.size());
        m.put("items", items.subList(0, n));
        if (n < items.size()) {
            m.put("truncated", true);
        }
        return m;
    }

    /**
     * Adds {@code table}, the rows rendered in the requested {@code table_format}, when the
     * caller asked for one. {@code columns} null takes every key of the rows.
     */
    public static void table(Map<String, Object> into, List<? extends Map<String, ?>> rows, List<String> columns, ToolArgs args) {
        if (!args.has("table_format")) {
            return;
        }
        Table.Format format;
        try {
            format = Table.Format.parse(args.require("table_format"));
        } catch (IllegalArgumentException e) {
            throw new ToolException("'table_format' must be one of " + List.of(Schema.TABLE_FORMATS));
        }
        if (rows.isEmpty()) {
            into.put("table", "");
            return;
        }
        List<Map<String, Object>> flat = rows.stream().map(Results::flatten).toList();
        into.put("table", Table.fromMaps(flat, columns).maxWidth(args.integer("table_max_width", 80)).render(format));
    }

    /** Lists become comma-separated text and nested maps {@code k=v} pairs, so a row fits a table. */
    private static Map<String, Object> flatten(Map<String, ?> row) {
        Map<String, Object> m = new LinkedHashMap<>();
        row.forEach((k, v) -> m.put(k, v instanceof List<?> l ? String.join(", ", l.stream().map(String::valueOf).toList())
                : v instanceof Map<?, ?> mm ? mm.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                        .collect(Collectors.joining(", "))
                        : v));
        return m;
    }
}
