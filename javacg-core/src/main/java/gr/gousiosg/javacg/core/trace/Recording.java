package gr.gousiosg.javacg.core.trace;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import gr.gousiosg.javacg.core.MethodId;
import gr.gousiosg.javacg.rt.TraceFormat;

/**
 * A recording directory written by the agent. {@link #open(Path)} reads the tables;
 * {@link #replay(TraceListener)} streams the events, so traces larger than memory can be
 * processed.
 */
public final class Recording {

    private final Path dir;
    private final Map<Integer, MethodId> methods = new LinkedHashMap<>();
    private final Map<MethodId, Integer> methodIds = new HashMap<>();
    private final Map<Integer, Integer> firstLines = new HashMap<>();
    private final Map<Integer, String> classNames = new HashMap<>();
    private final Map<Long, String> threads = new LinkedHashMap<>();
    private final Properties info = new Properties();

    private Recording(Path dir) {
        this.dir = dir;
    }

    public static Recording open(Path dir) throws IOException {
        Recording r = new Recording(dir);
        Path events = dir.resolve(TraceFormat.EVENTS);
        if (!Files.isRegularFile(events)) {
            throw new NoSuchFileException(events.toString(), null, "not a javacg recording");
        }
        Path symbols = dir.resolve(TraceFormat.SYMBOLS);
        if (Files.exists(symbols)) {
            for (String line : Files.readAllLines(symbols, StandardCharsets.UTF_8)) {
                String[] f = line.split("\t", -1);
                if (f.length >= 5 && f[1].equals("M")) {
                    int id = Integer.parseInt(f[0]);
                    MethodId m = MethodId.of(f[2], f[3], f[4]);
                    r.methods.put(id, m);
                    r.methodIds.put(m, id);
                    if (f.length >= 6) {
                        r.firstLines.put(id, Integer.parseInt(f[5]));
                    }
                } else if (f.length >= 3 && f[1].equals("C")) {
                    r.classNames.put(Integer.parseInt(f[0]), f[2]);
                }
            }
        }
        Path threads = dir.resolve(TraceFormat.THREADS);
        if (Files.exists(threads)) {
            for (String line : Files.readAllLines(threads, StandardCharsets.UTF_8)) {
                int tab = line.indexOf('\t');
                if (tab > 0) {
                    r.threads.put(Long.parseLong(line.substring(0, tab)), line.substring(tab + 1));
                }
            }
        }
        Path info = dir.resolve(TraceFormat.INFO);
        if (Files.exists(info)) {
            try (Reader in = Files.newBufferedReader(info, StandardCharsets.UTF_8)) {
                r.info.load(in);
            }
        }
        return r;
    }

    public Path directory() {
        return dir;
    }

    public String label() {
        return info.getProperty("label", dir.getFileName().toString());
    }

    /** False when the JVM died before the recording was stopped (tables may be incomplete). */
    public boolean isComplete() {
        return Boolean.parseBoolean(info.getProperty("complete", "false"));
    }

    /**
     * Converts an event's {@code System.nanoTime()} to epoch milliseconds, so recordings of
     * different JVMs share a time line. Returns -1 when the recording has no clock anchor.
     */
    public double toEpochMillis(long nanos) {
        String millis = info.getProperty("startedEpochMillis");
        String anchor = info.getProperty("startedNanoTime");
        if (millis == null || anchor == null) {
            return -1;
        }
        return Long.parseLong(millis) + (nanos - Long.parseLong(anchor)) / 1_000_000.0;
    }

    /** Method for an id; ids missing from the table map to a placeholder. */
    public MethodId method(int id) {
        MethodId m = methods.get(id);
        return m != null ? m : MethodId.of("?", "unknown#" + id, "()V");
    }

    public Integer methodId(MethodId m) {
        return methodIds.get(m);
    }

    /** Every method the agent instrumented, whether or not it ran. */
    public Collection<MethodId> instrumentedMethods() {
        return Collections.unmodifiableCollection(methods.values());
    }

    /** First source line of a method, or -1. */
    public int firstLine(int methodId) {
        return firstLines.getOrDefault(methodId, -1);
    }

    public String className(int id) {
        return classNames.getOrDefault(id, "?#" + id);
    }

    public String threadName(long thread) {
        return threads.getOrDefault(thread, "thread-" + thread);
    }

    public Map<Long, String> threads() {
        return Collections.unmodifiableMap(threads);
    }

    /** Streams all events to the listeners, in file order. */
    public void replay(TraceListener... listeners) throws IOException {
        List<TraceListener> ls = List.of(listeners);
        try (InputStream file = Files.newInputStream(dir.resolve(TraceFormat.EVENTS));
                DataInputStream in = new DataInputStream(new BufferedInputStream(file, 256 * 1024))) {
            if (in.readInt() != TraceFormat.MAGIC) {
                throw new IOException("Bad magic in " + dir);
            }
            int version = in.readInt();
            if (version != TraceFormat.VERSION) {
                throw new IOException("Unsupported trace version " + version + " in " + dir);
            }
            byte[] chunk = new byte[64 * 1024];
            while (true) {
                long thread;
                int length;
                try {
                    thread = in.readLong();
                    length = in.readInt();
                } catch (EOFException e) {
                    break;
                }
                if (length < 0) {
                    throw new IOException("Corrupt chunk length " + length + " in " + dir);
                }
                if (chunk.length < length) {
                    chunk = new byte[length];
                }
                try {
                    in.readFully(chunk, 0, length);
                } catch (EOFException e) {
                    break; // truncated by a JVM crash; keep what was complete
                }
                decode(thread, chunk, length, ls);
            }
        }
        ls.forEach(TraceListener::end);
    }

    private static void decode(long thread, byte[] b, int length, List<TraceListener> ls) throws IOException {
        int p = 0;
        while (p < length) {
            byte type = b[p++];
            switch (type) {
                case TraceFormat.ENTER, TraceFormat.EXIT -> {
                    int id = getInt(b, p);
                    long t = getLong(b, p + 4);
                    p += 12;
                    for (TraceListener l : ls) {
                        if (type == TraceFormat.ENTER) {
                            l.enter(thread, id, t);
                        } else {
                            l.exit(thread, id, t);
                        }
                    }
                }
                case TraceFormat.THROW, TraceFormat.LOCK, TraceFormat.UNLOCK -> {
                    int a = getInt(b, p);
                    int c = getInt(b, p + 4);
                    long t = getLong(b, p + 8);
                    p += 16;
                    for (TraceListener l : ls) {
                        switch (type) {
                            case TraceFormat.THROW -> l.exitThrow(thread, a, c, t);
                            case TraceFormat.LOCK -> l.lock(thread, a, c, t);
                            default -> l.unlock(thread, a, c, t);
                        }
                    }
                }
                case TraceFormat.ARGS -> {
                    int n = getInt(b, p);
                    String s = new String(b, p + 4, n, StandardCharsets.UTF_8);
                    p += 4 + n;
                    for (TraceListener l : ls) {
                        l.args(thread, s);
                    }
                }
                default -> throw new IOException("Unknown record type " + type + " at offset " + (p - 1));
            }
        }
    }

    private static int getInt(byte[] b, int p) {
        return (b[p] & 0xFF) << 24 | (b[p + 1] & 0xFF) << 16 | (b[p + 2] & 0xFF) << 8 | b[p + 3] & 0xFF;
    }

    private static long getLong(byte[] b, int p) {
        return (long) getInt(b, p) << 32 | getInt(b, p + 4) & 0xFFFFFFFFL;
    }
}
