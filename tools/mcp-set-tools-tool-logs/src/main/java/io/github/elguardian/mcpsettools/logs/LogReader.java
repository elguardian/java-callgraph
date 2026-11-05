package io.github.elguardian.mcpsettools.logs;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;

/**
 * Streams the records of log files: a line matching the format starts a record, the lines up to
 * the next record are its continuation (stack traces, multi-line messages). Lines before the
 * first record form a record without fields. Plain or gzip files.
 */
public final class LogReader {

    /** Continuation lines kept per record; the rest are counted, not kept. */
    public static final int MAX_CONTINUATION = 400;

    private LogReader() {
    }

    /** The first {@code n} lines of a file, e.g. to detect its format. */
    public static List<String> head(Path file, int n) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = open(file)) {
            String line;
            while (lines.size() < n && (line = r.readLine()) != null) {
                lines.add(line);
            }
        }
        return lines;
    }

    /** Feeds every record of the files to {@code sink}, file by file. */
    public static void read(List<Path> files, LogFormat format, Consumer<LogRecord> sink) throws IOException {
        for (Path f : files) {
            try (BufferedReader r = open(f)) {
                read(r, f, format, sink);
            }
        }
    }

    public static void read(BufferedReader r, Path file, LogFormat format, Consumer<LogRecord> sink) throws IOException {
        LogRecord current = null;
        List<String> continuation = new ArrayList<>();
        int dropped = 0;
        long n = 0;
        String line;
        while ((line = r.readLine()) != null) {
            n++;
            Optional<LogRecord> start = format.parseLine(line, file, n);
            if (start.isPresent()) {
                if (current != null || !continuation.isEmpty()) {
                    sink.accept(finish(current, continuation, dropped, file));
                }
                current = start.get();
                continuation = new ArrayList<>();
                dropped = 0;
            } else if (continuation.size() < MAX_CONTINUATION) {
                continuation.add(line);
            } else {
                dropped++;
            }
        }
        if (current != null || !continuation.isEmpty()) {
            sink.accept(finish(current, continuation, dropped, file));
        }
    }

    private static LogRecord finish(LogRecord r, List<String> continuation, int dropped, Path file) {
        if (dropped > 0) {
            continuation.add("[... " + dropped + " more lines]");
        }
        if (r == null) {
            // text before the first record
            return new LogRecord(file, 1, LogRecord.NO_TIME, null, null, null, null, null, continuation.get(0),
                    java.util.Map.of(), continuation.subList(1, continuation.size()));
        }
        return new LogRecord(r.file(), r.line(), r.micros(), r.timestamp(), r.level(), r.rawLevel(), r.thread(), r.logger(),
                r.message(), r.fields(), continuation);
    }

    static BufferedReader open(Path file) throws IOException {
        InputStream in = Files.newInputStream(file);
        if (file.getFileName().toString().endsWith(".gz")) {
            in = new GZIPInputStream(in);
        }
        return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    }
}
