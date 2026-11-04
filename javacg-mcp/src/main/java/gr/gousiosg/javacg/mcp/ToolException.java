package gr.gousiosg.javacg.mcp;

/** A tool call that cannot be answered; the message is returned to the client as a tool error. */
final class ToolException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    ToolException(String message) {
        super(message);
    }

    ToolException(String message, Throwable cause) {
        super(message, cause);
    }
}
