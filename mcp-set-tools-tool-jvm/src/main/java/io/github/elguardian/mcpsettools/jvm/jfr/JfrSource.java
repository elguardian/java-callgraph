package io.github.elguardian.mcpsettools.jvm.jfr;

import io.github.elguardian.mcpsettools.timeline.Span;
import io.github.elguardian.mcpsettools.timeline.Timeline;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

/**
 * Blocks from a JFR recording: monitor enters (waiting for a lock), parks, socket and file I/O
 * per thread, and GC pauses on their own lane. Short events are not recorded by JFR (thresholds
 * of the settings), so the timeline shows where threads waited, not everything they did.
 */
public final class JfrSource {

    private JfrSource() {
    }

    public static Timeline read(Path file, int maxSpans) throws IOException {
        List<Span> spans = new ArrayList<>();
        int dropped = 0;
        String process = file.getFileName().toString();
        try (RecordingFile r = new RecordingFile(file)) {
            while (r.hasMoreEvents()) {
                RecordedEvent e = r.readEvent();
                String type = e.getEventType().getName();
                String label;
                String category;
                switch (type) {
                    case "jdk.JavaMonitorEnter" -> {
                        label = "monitor " + cls(e, "monitorClass");
                        category = "lock-wait";
                    }
                    case "jdk.ThreadPark" -> {
                        label = "park " + cls(e, "parkedClass");
                        category = "park";
                    }
                    case "jdk.SocketRead", "jdk.SocketWrite" -> {
                        label = (type.endsWith("Read") ? "read " : "write ") + e.getString("host") + ":" + e.getInt("port");
                        category = "io";
                    }
                    case "jdk.FileRead", "jdk.FileWrite" -> {
                        label = (type.endsWith("Read") ? "read " : "write ") + e.getString("path");
                        category = "io";
                    }
                    case "jdk.GarbageCollection" -> {
                        long start = micros(e.getStartTime());
                        long pause = e.getDuration("sumOfPauses").toNanos() / 1000;
                        if (spans.size() < maxSpans) {
                            spans.add(new Span(process, "GC", start, start + Math.max(1, pause), e.getString("name"), "gc-pause",
                                    0, Map.of("cause", e.getString("cause"))));
                        }
                        continue;
                    }
                    default -> {
                        continue;
                    }
                }
                RecordedThread t = e.getThread();
                if (t == null) {
                    continue;
                }
                if (spans.size() >= maxSpans) {
                    dropped++;
                    continue;
                }
                Map<String, String> args = new LinkedHashMap<>();
                if (e.getStackTrace() != null && !e.getStackTrace().getFrames().isEmpty()) {
                    RecordedFrame f = e.getStackTrace().getFrames().get(0);
                    args.put("at", f.getMethod().getType().getName() + "." + f.getMethod().getName() + ":" + f.getLineNumber());
                }
                String name = t.getJavaName() != null ? t.getJavaName() : t.getOSName();
                spans.add(new Span(process, name, micros(e.getStartTime()), micros(e.getEndTime()), label, category, 0, args));
            }
        }
        List<String> notes = new ArrayList<>();
        notes.add("JFR records waits above the thresholds of its settings (20 ms for locks, parks and I/O in 'profile'); "
                + "shorter ones are not shown");
        if (dropped > 0) {
            notes.add(dropped + " events not collected (max " + maxSpans + ")");
        }
        return new Timeline(spans, List.of(), notes);
    }

    private static String cls(RecordedEvent e, String field) {
        return e.hasField(field) && e.getClass(field) != null ? e.getClass(field).getName() : "?";
    }

    private static long micros(Instant i) {
        return i.getEpochSecond() * 1_000_000 + i.getNano() / 1000;
    }
}
