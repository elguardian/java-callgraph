package io.github.elguardian.mcpsettools.rt;

import java.nio.charset.StandardCharsets;

/**
 * Static entry points called by instrumented bytecode. Every method returns immediately when
 * no recording is active and never throws into the application.
 */
public final class Probe {

    private static final int MAX_ARG_CHARS = 200;
    private static final int MAX_ARGS_BYTES = 4096;

    private Probe() {
    }

    public static void enter(int methodId) {
        if (!Recorder.active) {
            return;
        }
        try {
            ThreadBuffer b = Recorder.BUFFER.get();
            if (!b.busy) {
                b.method(TraceFormat.ENTER, methodId, System.nanoTime());
            }
        } catch (Throwable t) {
            Recorder.probeFailed(t);
        }
    }

    public static void exit(int methodId) {
        if (!Recorder.active) {
            return;
        }
        try {
            ThreadBuffer b = Recorder.BUFFER.get();
            if (!b.busy) {
                b.method(TraceFormat.EXIT, methodId, System.nanoTime());
            }
        } catch (Throwable t) {
            Recorder.probeFailed(t);
        }
    }

    /** Exceptional exit; called from the handler wrapping the method body, which then rethrows. */
    public static void exitThrow(Throwable thrown, int methodId) {
        if (!Recorder.active) {
            return;
        }
        try {
            ThreadBuffer b = Recorder.BUFFER.get();
            if (!b.busy) {
                int type = thrown == null ? 0 : Symbols.classOf(thrown);
                b.pair(TraceFormat.THROW, methodId, type, System.nanoTime());
            }
        } catch (Throwable t) {
            Recorder.probeFailed(t);
        }
    }

    /** Before {@code monitorenter}, a synchronized method body, or {@code Lock.lock()}. */
    public static void lock(Object monitor) {
        monitor(TraceFormat.LOCK, monitor);
    }

    /** Before {@code monitorexit}, leaving a synchronized method, or {@code Lock.unlock()}. */
    public static void unlock(Object monitor) {
        monitor(TraceFormat.UNLOCK, monitor);
    }

    /** Argument values of the method just entered (only emitted when argument capture is on). */
    public static void args(Object[] values, int methodId) {
        if (!Recorder.active || Recorder.argsMode == Recorder.ARGS_NONE) {
            return;
        }
        try {
            ThreadBuffer b = Recorder.BUFFER.get();
            if (b.busy) {
                return;
            }
            b.busy = true;
            String rendered;
            try {
                rendered = render(values, Recorder.argsMode == Recorder.ARGS_TOSTRING);
            } finally {
                b.busy = false;
            }
            byte[] bytes = rendered.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_ARGS_BYTES) {
                bytes = java.util.Arrays.copyOf(bytes, MAX_ARGS_BYTES);
            }
            b.bytes(TraceFormat.ARGS, bytes);
        } catch (Throwable t) {
            Recorder.probeFailed(t);
        }
    }

    private static void monitor(byte type, Object monitor) {
        if (!Recorder.active || monitor == null) {
            return;
        }
        try {
            ThreadBuffer b = Recorder.BUFFER.get();
            if (!b.busy) {
                b.pair(type, System.identityHashCode(monitor), Symbols.classOf(monitor), System.nanoTime());
            }
        } catch (Throwable t) {
            Recorder.probeFailed(t);
        }
    }

    private static String render(Object[] values, boolean callToString) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            Object v = values[i];
            if (v == null) {
                sb.append("null");
            } else if (!callToString) {
                sb.append(v.getClass().getName());
            } else {
                String s;
                try {
                    s = String.valueOf(v);
                } catch (Throwable t) {
                    s = "<" + t.getClass().getName() + " in toString>";
                }
                if (s.length() > MAX_ARG_CHARS) {
                    s = s.substring(0, MAX_ARG_CHARS) + "...";
                }
                sb.append(s);
            }
        }
        return sb.toString();
    }
}
