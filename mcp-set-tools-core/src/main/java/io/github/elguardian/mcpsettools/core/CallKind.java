package io.github.elguardian.mcpsettools.core;

/** How a call edge is made. The codes are the ones used by the original text output. */
public enum CallKind {
    /** {@code invokevirtual} */
    VIRTUAL('M'),
    /** {@code invokeinterface} */
    INTERFACE('I'),
    /** {@code invokespecial}: constructors, private and super calls */
    SPECIAL('O'),
    /** {@code invokestatic} */
    STATIC('S'),
    /** {@code invokedynamic}: lambda or method reference creation linked to its implementation */
    DYNAMIC('D'),
    /** Observed at runtime only; the static analysis has no matching call site. */
    RUNTIME('R');

    private final char code;

    CallKind(char code) {
        this.code = code;
    }

    public char code() {
        return code;
    }
}
