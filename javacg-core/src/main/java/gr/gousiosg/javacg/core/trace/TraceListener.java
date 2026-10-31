package gr.gousiosg.javacg.core.trace;

/**
 * Raw events of a recording, in per-thread order (events of different threads interleave in
 * chunk order). Ids refer to {@link Recording#method(int)} and {@link Recording#className(int)}.
 */
public interface TraceListener {

    default void enter(long thread, int method, long nanos) {
    }

    default void exit(long thread, int method, long nanos) {
    }

    default void exitThrow(long thread, int method, int exceptionClass, long nanos) {
    }

    default void lock(long thread, int identityHash, int lockClass, long nanos) {
    }

    default void unlock(long thread, int identityHash, int lockClass, long nanos) {
    }

    /** Argument values of the method most recently entered on {@code thread}. */
    default void args(long thread, String rendered) {
    }

    /** Called once after the last event. */
    default void end() {
    }
}
