package io.github.elguardian.mcpsettools.stat;

/** Where a class came from. Only {@link #APP} classes have their method bodies analyzed. */
public enum ClassScope {
    /** Application code: bodies are kept and analyzed. */
    APP,
    /** Library code on the analysis class path: signatures and hierarchy only. */
    LIB,
    /** Loaded on demand from the running JDK to complete the hierarchy. */
    JDK
}
