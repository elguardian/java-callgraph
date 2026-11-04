package io.github.elguardian.mcpsettools.core.trace;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Rebuilds per-thread call stacks from raw events and reports frames to a {@link Listener}.
 *
 * <p>The stream is not always balanced: a recording may start or stop mid-call, and a
 * constructor that throws before its super() call has no exit event. An exit whose method is
 * not on top of the stack therefore pops ("unwinds") the frames above the nearest matching
 * frame; an exit with no matching frame at all is counted and ignored.
 */
public final class CallStacks implements TraceListener {

    /** How a frame ended. */
    public enum Exit {
        NORMAL, EXCEPTION,
        /** No exit event; popped because an enclosing frame exited. */
        UNWOUND,
        /** Still on the stack when the recording ended. */
        OPEN
    }

    /** An active method invocation. */
    public static final class Frame {
        private final int method;
        private final long enterNanos;
        private long childNanos;
        private String args;
        private int pendingException;

        Frame(int method, long enterNanos) {
            this.method = method;
            this.enterNanos = enterNanos;
        }

        public int method() {
            return method;
        }

        public long enterNanos() {
            return enterNanos;
        }

        /** Inclusive time of the frame's completed children. */
        public long childNanos() {
            return childNanos;
        }

        /** Rendered arguments, or null if not captured. */
        public String args() {
            return args;
        }

        /**
         * Exception class id of the last callee that exited by throwing, until this frame does
         * anything else; 0 if none. In {@link Listener#onExit} for {@link Exit#EXCEPTION}, a
         * value equal to the frame's own exception class means it was propagated, not thrown here.
         */
        public int pendingException() {
            return pendingException;
        }
    }

    /** Receives reconstructed frames. All methods are optional. */
    public interface Listener {
        /** {@code stack} holds the frames below the new one (bottom first); do not modify. */
        default void onEnter(long thread, Frame frame, List<Frame> stack) {
        }

        default void onArgs(long thread, Frame frame) {
        }

        /**
         * @param stack          the frames remaining below (do not modify)
         * @param exceptionClass exception class id for {@link Exit#EXCEPTION}, else 0
         */
        default void onExit(long thread, Frame frame, List<Frame> stack, Exit how, long exitNanos,
                int exceptionClass) {
        }

        default void onLock(long thread, List<Frame> stack, int identityHash, int lockClass, long nanos,
                boolean acquire) {
        }

        /** A callee of {@code frame} threw and {@code frame} carried on: it caught the exception. */
        default void onCaught(long thread, Frame frame, int exceptionClass) {
        }

        default void onEnd() {
        }
    }

    private final Listener listener;
    private final Map<Long, List<Frame>> stacks = new HashMap<>();
    private final Map<Long, Long> lastNanos = new HashMap<>();
    private long unmatchedExits;
    private long unwound;

    public CallStacks(Listener listener) {
        this.listener = listener;
    }

    /** Exit events with no matching frame (the recording started inside the call). */
    public long unmatchedExits() {
        return unmatchedExits;
    }

    /** Frames that never got their own exit event. */
    public long unwoundFrames() {
        return unwound;
    }

    @Override
    public void enter(long thread, int method, long nanos) {
        List<Frame> stack = stack(thread);
        if (!stack.isEmpty()) {
            caught(thread, stack.get(stack.size() - 1));
        }
        Frame f = new Frame(method, nanos);
        listener.onEnter(thread, f, stack);
        stack.add(f);
        lastNanos.put(thread, nanos);
    }

    @Override
    public void args(long thread, String rendered) {
        List<Frame> stack = stack(thread);
        if (!stack.isEmpty()) {
            Frame top = stack.get(stack.size() - 1);
            top.args = rendered;
            listener.onArgs(thread, top);
        }
    }

    @Override
    public void exit(long thread, int method, long nanos) {
        pop(thread, method, nanos, Exit.NORMAL, 0);
    }

    @Override
    public void exitThrow(long thread, int method, int exceptionClass, long nanos) {
        pop(thread, method, nanos, Exit.EXCEPTION, exceptionClass);
    }

    @Override
    public void lock(long thread, int identityHash, int lockClass, long nanos) {
        listener.onLock(thread, stack(thread), identityHash, lockClass, nanos, true);
    }

    @Override
    public void unlock(long thread, int identityHash, int lockClass, long nanos) {
        listener.onLock(thread, stack(thread), identityHash, lockClass, nanos, false);
    }

    @Override
    public void end() {
        for (Map.Entry<Long, List<Frame>> e : stacks.entrySet()) {
            List<Frame> stack = e.getValue();
            long last = lastNanos.getOrDefault(e.getKey(), 0L);
            while (!stack.isEmpty()) {
                Frame f = stack.remove(stack.size() - 1);
                listener.onExit(e.getKey(), f, stack, Exit.OPEN, Math.max(last, f.enterNanos), 0);
            }
        }
        listener.onEnd();
    }

    private void pop(long thread, int method, long nanos, Exit how, int exceptionClass) {
        List<Frame> stack = stack(thread);
        lastNanos.put(thread, nanos);
        int match = stack.size() - 1;
        while (match >= 0 && stack.get(match).method != method) {
            match--;
        }
        if (match < 0) {
            unmatchedExits++;
            return;
        }
        while (stack.size() - 1 > match) {
            Frame f = stack.remove(stack.size() - 1);
            unwound++;
            finish(thread, f, stack, Exit.UNWOUND, nanos, 0);
        }
        Frame f = stack.remove(stack.size() - 1);
        finish(thread, f, stack, how, nanos, exceptionClass);
    }

    private void finish(long thread, Frame f, List<Frame> stack, Exit how, long nanos, int exceptionClass) {
        if (how == Exit.NORMAL || how == Exit.EXCEPTION && f.pendingException != exceptionClass) {
            caught(thread, f);
        }
        if (!stack.isEmpty()) {
            Frame parent = stack.get(stack.size() - 1);
            parent.childNanos += nanos - f.enterNanos;
            if (how == Exit.EXCEPTION) {
                parent.pendingException = exceptionClass;
            }
        }
        listener.onExit(thread, f, stack, how, nanos, exceptionClass);
    }

    private void caught(long thread, Frame f) {
        if (f.pendingException != 0) {
            listener.onCaught(thread, f, f.pendingException);
            f.pendingException = 0;
        }
    }

    private List<Frame> stack(long thread) {
        return stacks.computeIfAbsent(thread, k -> new ArrayList<>());
    }
}
