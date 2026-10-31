package gr.gousiosg.javacg.rt;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Controls recordings. A recording is one directory of trace files (see {@link TraceFormat});
 * {@link #start(String)} opens a new one, {@link #stop()} completes it. Code running in the
 * traced JVM — e.g. an Arquillian test inside a deployment — can call these methods directly;
 * remote clients use the {@code javacg:type=Recorder} MBean.
 *
 * <p>Lock order: a thread buffer's monitor is always taken before {@link #LOCK}, never the
 * other way round.
 */
public final class Recorder {

    /** Read by every probe; false drops events. */
    static volatile boolean active;

    static final int ARGS_NONE = 0;
    static final int ARGS_TYPES = 1;
    static final int ARGS_TOSTRING = 2;
    static volatile int argsMode = ARGS_NONE;

    private static final Object LOCK = new Object();
    private static final ConcurrentLinkedQueue<ThreadBuffer> BUFFERS = new ConcurrentLinkedQueue<>();
    static final ThreadLocal<ThreadBuffer> BUFFER = ThreadLocal.withInitial(() -> {
        ThreadBuffer b = new ThreadBuffer(Thread.currentThread());
        BUFFERS.add(b);
        return b;
    });
    /** Names of finished threads whose buffers were dropped from {@link #BUFFERS}. */
    private static final Map<Long, String> RETIRED = new ConcurrentHashMap<>();

    private static volatile Path outputDirectory = defaultOutputDirectory();
    private static volatile boolean verbose;
    private static boolean hookInstalled;
    private static Recording current;
    private static volatile boolean failureLogged;

    private Recorder() {
    }

    /**
     * Configures the recorder; called once by the agent before any class is instrumented.
     *
     * @param out  base output directory, or null for {@code ${java.io.tmpdir}/javacg/<pid>}
     * @param args argument capture: {@code none}, {@code types} or {@code tostring}
     */
    public static void configure(String out, String args, boolean beVerbose) {
        if (out != null && !out.isEmpty()) {
            outputDirectory = Paths.get(out);
        }
        argsMode = switch (args == null ? "none" : args.toLowerCase(Locale.ROOT)) {
            case "types" -> ARGS_TYPES;
            case "tostring" -> ARGS_TOSTRING;
            default -> ARGS_NONE;
        };
        verbose = beVerbose;
        synchronized (LOCK) {
            if (!hookInstalled) {
                hookInstalled = true;
                Runtime.getRuntime().addShutdownHook(new Thread(Recorder::stop, "javacg-shutdown"));
            }
        }
    }

    /**
     * Completes the current recording, if any, and starts a new one in
     * {@code <out>/<label>} (a numeric suffix is added if that directory exists).
     *
     * @return the new recording directory, or null if it could not be created
     */
    public static String start(String label) {
        flushBuffers();
        synchronized (LOCK) {
            closeCurrent();
            try {
                current = Recording.open(outputDirectory, label == null || label.isEmpty() ? "default" : label);
                active = true;
                log("recording " + current.dir);
                return current.dir.toString();
            } catch (IOException | RuntimeException e) {
                active = false;
                warn("cannot start recording in " + outputDirectory + ": " + e);
                return null;
            }
        }
    }

    /**
     * Stops recording and writes the symbol and thread tables.
     *
     * @return the completed recording directory, or null if nothing was recording
     */
    public static String stop() {
        active = false;
        flushBuffers();
        synchronized (LOCK) {
            return closeCurrent();
        }
    }

    /** Writes buffered events and tables without stopping, so a running recording can be read. */
    public static void dump() {
        flushBuffers();
        synchronized (LOCK) {
            if (current != null) {
                try {
                    current.writeTables(false);
                } catch (IOException e) {
                    warn("dump failed: " + e);
                }
            }
        }
    }

    public static boolean isRecording() {
        return active;
    }

    /** Label of the current recording, or null. */
    public static String label() {
        synchronized (LOCK) {
            return current == null ? null : current.label;
        }
    }

    /** Directory of the current recording, or null. */
    public static String recordingDirectory() {
        synchronized (LOCK) {
            return current == null ? null : current.dir.toString();
        }
    }

    public static String outputDirectory() {
        return outputDirectory.toString();
    }

    static void writeChunk(long threadId, byte[] data, int length) {
        synchronized (LOCK) {
            if (current == null) {
                return;
            }
            try {
                current.out.writeLong(threadId);
                current.out.writeInt(length);
                current.out.write(data, 0, length);
            } catch (IOException e) {
                active = false;
                warn("cannot write trace, recording disabled: " + e);
            }
        }
    }

    /** Reports a probe failure once; probes never propagate exceptions into the application. */
    static void probeFailed(Throwable t) {
        if (!failureLogged) {
            failureLogged = true;
            warn("probe failure (further failures not reported): " + t);
        }
    }

    static void log(String msg) {
        if (verbose) {
            System.err.println("[javacg] " + msg);
        }
    }

    static void warn(String msg) {
        System.err.println("[javacg] " + msg);
    }

    private static void flushBuffers() {
        for (ThreadBuffer b : BUFFERS) {
            b.flush();
            if (b.isDead() && b.isEmpty()) {
                RETIRED.put(b.threadId, b.threadName());
                BUFFERS.remove(b);
            }
        }
    }

    /** Must hold {@link #LOCK}. */
    private static String closeCurrent() {
        if (current == null) {
            return null;
        }
        Recording r = current;
        current = null;
        try {
            r.writeTables(true);
            r.out.close();
        } catch (IOException e) {
            warn("cannot complete recording " + r.dir + ": " + e);
        }
        log("recording complete " + r.dir);
        return r.dir.toString();
    }

    private static Path defaultOutputDirectory() {
        return Paths.get(System.getProperty("java.io.tmpdir"), "javacg", Long.toString(ProcessHandle.current().pid()));
    }

    private static final class Recording {
        final String label;
        final Path dir;
        final DataOutputStream out;
        final Instant started = Instant.now();

        private Recording(String label, Path dir, DataOutputStream out) {
            this.label = label;
            this.dir = dir;
            this.out = out;
        }

        static Recording open(Path base, String label) throws IOException {
            String safe = label.replaceAll("[^A-Za-z0-9._-]", "_");
            Path dir = base.resolve(safe);
            for (int n = 2; Files.exists(dir); n++) {
                dir = base.resolve(safe + "-" + n);
            }
            Files.createDirectories(dir);
            OutputStream file = Files.newOutputStream(dir.resolve(TraceFormat.EVENTS));
            try {
                DataOutputStream out = new DataOutputStream(new BufferedOutputStream(file, 256 * 1024));
                out.writeInt(TraceFormat.MAGIC);
                out.writeInt(TraceFormat.VERSION);
                return new Recording(label, dir, out);
            } catch (IOException | RuntimeException e) {
                file.close();
                throw e;
            }
        }

        void writeTables(boolean complete) throws IOException {
            out.flush();
            Symbols.write(dir.resolve(TraceFormat.SYMBOLS));
            Map<Long, String> threads = new TreeMap<>(RETIRED);
            for (ThreadBuffer b : BUFFERS) {
                threads.put(b.threadId, b.threadName());
            }
            try (Writer w = Files.newBufferedWriter(dir.resolve(TraceFormat.THREADS), StandardCharsets.UTF_8)) {
                for (Map.Entry<Long, String> e : threads.entrySet()) {
                    w.write(e.getKey() + "\t" + e.getValue().replace('\t', ' ').replace('\n', ' ') + "\n");
                }
            }
            Properties p = new Properties();
            p.setProperty("label", label);
            p.setProperty("pid", Long.toString(ProcessHandle.current().pid()));
            p.setProperty("started", started.toString());
            p.setProperty(complete ? "stopped" : "dumped", Instant.now().toString());
            p.setProperty("complete", Boolean.toString(complete));
            try (Writer w = Files.newBufferedWriter(dir.resolve(TraceFormat.INFO), StandardCharsets.UTF_8)) {
                p.store(w, "javacg recording");
            }
        }
    }
}
