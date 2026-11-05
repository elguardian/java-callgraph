package io.github.elguardian.mcpsettools.commons.stack;

import java.util.ArrayList;
import java.util.List;

/**
 * A parsed Java stack trace.
 *
 * @param exception  exception class name
 * @param message    message, possibly multi-line; null when there is none
 * @param frames     the {@code at ...} lines
 * @param omitted    frames left out as {@code ... N more} (shared with the enclosing trace)
 * @param cause      the {@code Caused by:} trace, or null
 * @param suppressed the {@code Suppressed:} traces
 */
public record StackTrace(String exception, String message, List<Frame> frames, int omitted, StackTrace cause,
        List<StackTrace> suppressed) {

    /**
     * One frame: {@code at [loader/][module[@version]/]class.method(file:line)}.
     *
     * @param line source line, or -1 (unknown, native)
     */
    public record Frame(String className, String method, String file, int line, String module) {

        public boolean isNative() {
            return "Native Method".equals(file);
        }

        /** {@code class.method}, without the line: stable across edits of the file. */
        public String location() {
            return className + "." + method;
        }

        @Override
        public String toString() {
            String src = file == null ? "Unknown Source" : line > 0 ? file + ":" + line : file;
            return (module == null ? "" : module + "/") + className + "." + method + "(" + src + ")";
        }
    }

    public StackTrace {
        frames = List.copyOf(frames);
        suppressed = List.copyOf(suppressed);
    }

    /** This trace followed by its causes. */
    public List<StackTrace> chain() {
        List<StackTrace> all = new ArrayList<>();
        for (StackTrace t = this; t != null; t = t.cause()) {
            all.add(t);
        }
        return all;
    }

    /** The innermost cause. */
    public StackTrace root() {
        StackTrace t = this;
        while (t.cause() != null) {
            t = t.cause();
        }
        return t;
    }

    /** {@code exception: message}, as the header line prints it. */
    public String header() {
        return message == null ? exception : exception + ": " + message;
    }
}
