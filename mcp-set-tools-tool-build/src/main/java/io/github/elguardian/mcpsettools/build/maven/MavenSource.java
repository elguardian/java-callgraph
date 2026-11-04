package io.github.elguardian.mcpsettools.build.maven;

import io.github.elguardian.mcpsettools.timeline.Span;
import io.github.elguardian.mcpsettools.timeline.Timeline;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.elguardian.mcpsettools.build.maven.MavenBuild;

/** Module builds of a Maven log with timestamps (maven_run writes them), on their builder threads. */
public final class MavenSource {

    private MavenSource() {
    }

    public static Timeline read(MavenBuild build) {
        List<Span> spans = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        int untimed = 0;
        for (MavenBuild.Module m : build.modules()) {
            if (m.start() == Long.MIN_VALUE) {
                untimed++;
                continue;
            }
            Map<String, String> args = new LinkedHashMap<>();
            if (m.status() != null) {
                args.put("status", m.status());
            }
            if (m.seconds() >= 0) {
                args.put("reactor_summary_seconds", String.valueOf(m.seconds()));
            }
            args.put("executions", String.join(", ", m.executions()));
            String label = m.artifactId() != null ? m.artifactId() : m.name();
            spans.add(new Span("maven", m.thread() == null ? "main" : m.thread(), m.start(), m.end(), label,
                    m.status() == null ? "module" : m.status().toLowerCase(java.util.Locale.ROOT), 0, args));
        }
        if (untimed > 0) {
            notes.add(untimed + " module(s) have no timestamped lines (skipped modules, or a log without timestamps: "
                    + "maven_run adds them)");
        }
        return new Timeline(spans, List.of(), notes);
    }
}
