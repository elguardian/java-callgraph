package io.github.elguardian.mcpsettools.tool;

/** A tool call that cannot be answered; the message is returned to the client as a tool error. */
public final class ToolException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ToolException(String message) {
        super(message);
    }

    public ToolException(String message, Throwable cause) {
        super(message, cause);
    }
}
