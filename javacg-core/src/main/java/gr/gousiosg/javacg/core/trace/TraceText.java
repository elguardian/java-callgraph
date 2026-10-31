package gr.gousiosg.javacg.core.trace;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import gr.gousiosg.javacg.core.MethodId;

/**
 * Dumps a recording in the original {@code calltrace.txt} line format:
 *
 * <pre>
 * &gt;[depth][tid]fully.qualified.Class:method=nanos                 entry
 * &gt;[depth][tid]fully.qualified.Class:method [a, b]=nanos          entry with captured arguments
 * &lt;[depth][tid]fully.qualified.Class:method=nanos                 normal exit
 * &lt;[depth][tid]fully.qualified.Class:method!java.io.IOException=nanos   exceptional exit
 * </pre>
 *
 * Newlines, '=' and '!' inside argument values are escaped, so every event stays one line.
 */
public final class TraceText {

    private TraceText() {
    }

    public static void write(Recording recording, Writer out) throws IOException {
        PrintWriter w = new PrintWriter(out);
        Map<Long, String> pendingEnter = new HashMap<>();
        CallStacks stacks = new CallStacks(new CallStacks.Listener() {
            @Override
            public void onEnter(long thread, CallStacks.Frame frame, List<CallStacks.Frame> stack) {
                flush(thread);
                pendingEnter.put(thread, ">[" + stack.size() + "][" + thread + "]" + name(frame) + "\u0000="
                        + frame.enterNanos());
            }

            @Override
            public void onArgs(long thread, CallStacks.Frame frame) {
                String line = pendingEnter.get(thread);
                if (line != null) {
                    pendingEnter.put(thread, line.replace("\u0000", " [" + escape(frame.args()) + "]"));
                }
            }

            @Override
            public void onExit(long thread, CallStacks.Frame frame, List<CallStacks.Frame> stack, CallStacks.Exit how,
                    long exitNanos, int exceptionClass) {
                flush(thread);
                if (how == CallStacks.Exit.OPEN) {
                    return;
                }
                String suffix = switch (how) {
                    case EXCEPTION -> "!" + recording.className(exceptionClass);
                    case UNWOUND -> "!<unwound>";
                    default -> "";
                };
                w.println("<[" + stack.size() + "][" + thread + "]" + name(frame) + suffix + "=" + exitNanos);
            }

            @Override
            public void onEnd() {
                pendingEnter.keySet().stream().toList().forEach(this::flush);
            }

            private void flush(long thread) {
                String line = pendingEnter.remove(thread);
                if (line != null) {
                    w.println(line.replace("\u0000", ""));
                }
            }

            private String name(CallStacks.Frame f) {
                MethodId m = recording.method(f.method());
                return m.className() + ":" + m.name();
            }
        });
        recording.replay(stacks);
        w.flush();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("=", "\\u003d")
                .replace("!", "\\u0021").replace("\u0000", "");
    }
}
