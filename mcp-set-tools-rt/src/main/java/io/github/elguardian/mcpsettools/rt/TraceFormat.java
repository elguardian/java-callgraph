package io.github.elguardian.mcpsettools.rt;

/**
 * Layout of a recording directory, shared by the writer ({@link Recorder}) and the reader in
 * {@code mcp-set-tools-core}.
 *
 * <pre>
 * &lt;out&gt;/&lt;label&gt;/
 *   events.bin            int MAGIC, int VERSION, then chunks:
 *                           long threadId, int byteLength, records...
 *   symbols.tsv           id \t M \t owner \t name \t descriptor \t firstLine
 *                         id \t C \t className
 *   threads.tsv           threadId \t threadName
 *   recording.properties  label, pid, start/stop wall clock, and the nanoTime at start
 *                         (startedEpochMillis/startedNanoTime) to convert event times
 * </pre>
 *
 * Records (big-endian, as written by {@code DataOutputStream}):
 *
 * <pre>
 * ENTER  byte, int methodId, long nanoTime
 * EXIT   byte, int methodId, long nanoTime
 * THROW  byte, int methodId, int exceptionClassId, long nanoTime   (exceptional exit)
 * LOCK   byte, int identityHash, int lockClassId, long nanoTime    (before acquiring)
 * UNLOCK byte, int identityHash, int lockClassId, long nanoTime    (before releasing)
 * ARGS   byte, int byteLength, UTF-8 bytes                         (follows its ENTER)
 * </pre>
 */
public final class TraceFormat {

    public static final int MAGIC = 0x4A434754; // "JCGT"
    public static final int VERSION = 1;

    public static final byte ENTER = 1;
    public static final byte EXIT = 2;
    public static final byte THROW = 3;
    public static final byte LOCK = 4;
    public static final byte UNLOCK = 5;
    public static final byte ARGS = 6;

    public static final String EVENTS = "events.bin";
    public static final String SYMBOLS = "symbols.tsv";
    public static final String THREADS = "threads.tsv";
    public static final String INFO = "recording.properties";

    private TraceFormat() {
    }
}
