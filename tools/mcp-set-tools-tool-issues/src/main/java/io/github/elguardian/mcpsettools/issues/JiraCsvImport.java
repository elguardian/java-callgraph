package io.github.elguardian.mcpsettools.issues;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import io.github.elguardian.mcpsettools.commons.stack.StackTrace;
import io.github.elguardian.mcpsettools.commons.stack.StackTraceParser;
import io.github.elguardian.mcpsettools.commons.table.Csv;

/**
 * Known issues from a Jira CSV export: one per distinct stack trace found in an issue's
 * description and comments. Repeated columns (Fix Version/s, Comment) are all read.
 */
public final class JiraCsvImport {

    public record Result(List<KnownIssue> issues, int rows, int rowsWithoutTrace) {
    }

    private JiraCsvImport() {
    }

    public static Result read(String csv, String baseUrl, List<String> appPackages) {
        List<List<String>> records = Csv.parse(csv, ',');
        if (records.isEmpty()) {
            throw new IllegalArgumentException("Empty CSV");
        }
        List<String> header = records.get(0);
        int key = column(header, "Issue key");
        if (key < 0) {
            throw new IllegalArgumentException("No 'Issue key' column: is it a Jira CSV export? Columns: " + header);
        }
        int summary = column(header, "Summary");
        int status = column(header, "Status");
        List<KnownIssue> issues = new ArrayList<>();
        int without = 0;
        for (List<String> row : records.subList(1, records.size())) {
            if (row.size() <= key || row.get(key).isBlank()) {
                continue;
            }
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < header.size() && i < row.size(); i++) {
                String h = header.get(i);
                if (h.equals("Description") || h.startsWith("Comment") || h.equals("Environment")) {
                    // Jira comments are "date;author;body"
                    text.append(row.get(i).replaceFirst("^\\d{2}/\\w{3}/\\d{2} [^;]*;[^;]*;", "")).append('\n');
                }
            }
            List<StackTrace> traces = StackTraceParser.parseAll(text.toString().replace("{code}", "\n").replace("{noformat}", "\n"));
            if (traces.isEmpty()) {
                without++;
                continue;
            }
            String k = row.get(key).strip();
            Set<String> seen = new LinkedHashSet<>();
            for (StackTrace t : traces) {
                KnownIssue.Fingerprint f = KnownIssue.Fingerprint.of(t, appPackages);
                if (seen.add(f.signatureId())) {
                    issues.add(KnownIssue.of(k, cell(row, summary), cell(row, status), all(header, row, "Fix Version/s"),
                            all(header, row, "Affects Version/s"), baseUrl == null ? null
                                    : baseUrl.replaceAll("/+$", "") + "/browse/" + k, f, null, "jira-csv"));
                }
            }
        }
        return new Result(issues, records.size() - 1, without);
    }

    private static int column(List<String> header, String name) {
        return header.indexOf(name);
    }

    private static String cell(List<String> row, int i) {
        return i < 0 || i >= row.size() || row.get(i).isBlank() ? null : row.get(i).strip();
    }

    private static List<String> all(List<String> header, List<String> row, String name) {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < header.size() && i < row.size(); i++) {
            if (header.get(i).equals(name) && !row.get(i).isBlank()) {
                values.add(row.get(i).strip());
            }
        }
        return values;
    }
}
