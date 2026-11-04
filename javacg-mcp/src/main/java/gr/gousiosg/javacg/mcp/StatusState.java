package gr.gousiosg.javacg.mcp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.json.McpJsonDefaults;

/** What the last run saw, to mark items new or changed since then. */
final class StatusState {

    final Path file;
    Instant lastRun;
    final Map<String, String> updated = new LinkedHashMap<>();

    private StatusState(Path file) {
        this.file = file;
    }

    static Path defaultFile(StatusConfig config) {
        return config.file.resolveSibling("status-state.json");
    }

    static StatusState load(Path file) {
        StatusState s = new StatusState(file);
        try {
            if (Files.isRegularFile(file)) {
                Object root = StatusJson.parse(Files.readString(file));
                s.lastRun = StatusJson.time(StatusJson.str(root, "last_run"));
                if (StatusJson.at(root, "items") instanceof Map<?, ?> m) {
                    m.forEach((k, v) -> s.updated.put(k.toString(), String.valueOf(v)));
                }
            }
        } catch (IOException e) {
            // a broken state file only loses the NEW markers
        }
        return s;
    }

    /** New or changed: not seen last time, or updated since. */
    boolean changed(WorkItem i) {
        String before = updated.get(i.key());
        return before == null || i.updated != null && !before.equals(i.updated.toString());
    }

    void save(List<WorkItem> items, Instant now) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("last_run", now.toString());
        Map<String, Object> m = new LinkedHashMap<>();
        items.forEach(i -> m.put(i.key(), i.updated == null ? "" : i.updated.toString()));
        root.put("items", m);
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = Files.createTempFile(file.toAbsolutePath().getParent(), ".status", ".tmp");
        Files.writeString(tmp, McpJsonDefaults.getMapper().writeValueAsString(root));
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
