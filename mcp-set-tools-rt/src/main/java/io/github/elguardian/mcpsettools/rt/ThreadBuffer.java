package io.github.elguardian.mcpsettools.rt;

import java.lang.ref.WeakReference;

/**
 * Per-thread event buffer. Only its own thread appends, so the monitor is uncontended except
 * while {@link Recorder} flushes all buffers on stop/dump.
 */
final class ThreadBuffer {

    static final int CAPACITY = 32 * 1024;

    final long threadId;
    private final String initialName;
    private final WeakReference<Thread> thread;
    private byte[] buf = new byte[CAPACITY];
    private int pos;
    /** Set while the probe runs code that may re-enter instrumented methods (argument toString). */
    boolean busy;

    @SuppressWarnings("deprecation") // Thread.threadId() is Java 19+; the runtime targets 17
    ThreadBuffer(Thread t) {
        this.threadId = t.getId();
        this.initialName = t.getName();
        this.thread = new WeakReference<>(t);
    }

    String threadName() {
        Thread t = thread.get();
        return t == null ? initialName : t.getName();
    }

    boolean isDead() {
        Thread t = thread.get();
        return t == null || !t.isAlive();
    }

    synchronized void method(byte type, int id, long time) {
        ensure(13);
        buf[pos++] = type;
        putInt(id);
        putLong(time);
    }

    synchronized void pair(byte type, int a, int b, long time) {
        ensure(17);
        buf[pos++] = type;
        putInt(a);
        putInt(b);
        putLong(time);
    }

    synchronized void bytes(byte type, byte[] data) {
        ensure(5 + data.length);
        buf[pos++] = type;
        putInt(data.length);
        System.arraycopy(data, 0, buf, pos, data.length);
        pos += data.length;
    }

    synchronized void flush() {
        if (pos > 0) {
            Recorder.writeChunk(threadId, buf, pos);
            pos = 0;
        }
    }

    synchronized boolean isEmpty() {
        return pos == 0;
    }

    private void ensure(int n) {
        if (pos + n > buf.length) {
            flush();
            if (n > buf.length) {
                buf = new byte[n];
            }
        }
    }

    private void putInt(int v) {
        buf[pos++] = (byte) (v >>> 24);
        buf[pos++] = (byte) (v >>> 16);
        buf[pos++] = (byte) (v >>> 8);
        buf[pos++] = (byte) v;
    }

    private void putLong(long v) {
        putInt((int) (v >>> 32));
        putInt((int) v);
    }
}
