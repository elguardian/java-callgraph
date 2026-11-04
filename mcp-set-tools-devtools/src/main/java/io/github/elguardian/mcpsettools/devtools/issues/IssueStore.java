package io.github.elguardian.mcpsettools.devtools.issues;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Known issues in a JSON-lines file, one issue and trace per line; a file can be kept in a team
 * repository. Default {@code $MCPSETTOOLS_ISSUES}, else {@code ~/.mcp-set-tools/known-issues.jsonl}.
 */
public final class IssueStore {

    private final Path file;

    public IssueStore(Path file) {
        this.file = file;
    }

    public static Path defaultFile() {
        String env = System.getenv("MCPSETTOOLS_ISSUES");
        return env != null && !env.isBlank() ? Path.of(env) : Path.of(System.getProperty("user.home"), ".mcp-set-tools",
                "known-issues.jsonl");
    }

    public Path file() {
        return file;
    }

    public List<KnownIssue> load() throws IOException {
        List<KnownIssue> issues = new ArrayList<>();
        if (!Files.isRegularFile(file)) {
            return issues;
        }
        int n = 0;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            n++;
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            try {
                issues.add(KnownIssue.fromMap(JsonLine.read(line)));
            } catch (RuntimeException e) {
                throw new IOException(file + ":" + n + ": not a known-issue line: " + e.getMessage(), e);
            }
        }
        return issues;
    }

    /** Adds issues, replacing entries with the same key and signature; returns how many were new. */
    public int add(List<KnownIssue> added) throws IOException {
        List<KnownIssue> all = load();
        int fresh = 0;
        for (KnownIssue i : added) {
            boolean replaced = all.removeIf(x -> x.key().equals(i.key()) && x.signatureId().equals(i.signatureId()));
            if (!replaced) {
                fresh++;
            }
            all.add(i);
        }
        Path parent = file.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path tmp = Files.createTempFile(parent, ".known-issues", ".tmp");
        StringBuilder sb = new StringBuilder();
        all.forEach(i -> sb.append(JsonLine.write(i.toMap())).append('\n'));
        Files.writeString(tmp, sb, StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return fresh;
    }
}
