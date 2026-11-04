package io.github.elguardian.mcpsettools.timeline;

import java.time.ZoneId;

import io.github.elguardian.mcpsettools.tool.Schema;
import io.github.elguardian.mcpsettools.tool.ToolArgs;
import io.github.elguardian.mcpsettools.tool.ToolContext;

/**
 * Where timeline blocks come from; found through {@link java.util.ServiceLoader} by the timeline
 * tool, so a module owning some data (logs, recordings, GC logs) contributes its source without
 * the tool depending on it. List implementations in
 * {@code META-INF/services/io.github.elguardian.mcpsettools.timeline.TimelineSource}.
 */
public interface TimelineSource {

    /** What a request carries besides the arguments. */
    record Request(ZoneId zone, int maxSpans, ToolContext context) {
    }

    /** The value of the timeline tool's {@code source} argument. */
    String name();

    /** Position in the list of sources shown to clients (lower first). */
    default int order() {
        return 100;
    }

    /** Adds this source's arguments to the tool's schema; descriptions start with {@code "<name>: "}. */
    void arguments(Schema schema);

    Timeline read(ToolArgs args, Request request) throws Exception;
}
