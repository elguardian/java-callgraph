package io.github.elguardian.mcpsettools.tool;

/**
 * Messages from a tool to the client while it works. A host maps them to its protocol (MCP
 * {@code notifications/progress} and {@code notifications/message}); without a host they go nowhere.
 * Notifiers never throw.
 */
public interface Notifier {

    /** Log levels, as in MCP (and syslog). */
    enum Level {
        DEBUG, INFO, NOTICE, WARNING, ERROR
    }

    /** Drops everything. */
    Notifier NONE = new Notifier() {
        @Override
        public void progress(double done, double total, String message) {
        }

        @Override
        public void log(Level level, String message) {
        }
    };

    /**
     * Progress of the call in flight: {@code done} out of {@code total} ({@code total <= 0} when
     * unknown). Ignored once the call has returned, and when the client asked for no progress.
     */
    void progress(double done, double total, String message);

    /** A message for the client's log; also valid after the call returned, e.g. from a background job. */
    void log(Level level, String message);
}
