package io.github.elguardian.mcpsettools.commons.table;

import java.util.ArrayList;
import java.util.List;

/** RFC 4180 reading and writing; with a tab separator, TSV (no quoting, tabs and newlines become spaces). */
public final class Csv {

    private Csv() {
    }

    public static List<List<String>> parse(String text, char sep) {
        List<List<String>> records = new ArrayList<>();
        List<String> record = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(c);
                }
                continue;
            }
            if (c == '"' && field.isEmpty() && sep != '\t') {
                quoted = true;
                any = true;
            } else if (c == sep) {
                record.add(field.toString());
                field.setLength(0);
                any = true;
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                if (any || !field.isEmpty()) {
                    record.add(field.toString());
                    records.add(record);
                }
                record = new ArrayList<>();
                field.setLength(0);
                any = false;
            } else {
                field.append(c);
            }
        }
        if (quoted) {
            throw new IllegalArgumentException("Unterminated quoted CSV field");
        }
        if (any || !field.isEmpty()) {
            record.add(field.toString());
            records.add(record);
        }
        return records;
    }

    static void write(StringBuilder sb, List<String> cells, char sep) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                sb.append(sep);
            }
            String c = cells.get(i);
            if (sep == '\t') {
                sb.append(c.replace('\t', ' ').replace('\n', ' ').replace('\r', ' '));
            } else if (c.indexOf(sep) >= 0 || c.indexOf('"') >= 0 || c.indexOf('\n') >= 0 || c.indexOf('\r') >= 0) {
                sb.append('"').append(c.replace("\"", "\"\"")).append('"');
            } else {
                sb.append(c);
            }
        }
        sb.append('\n');
    }
}
