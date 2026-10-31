package gr.gousiosg.javacg.core;

/** Which analysis produced a call edge. */
public enum Provenance {
    STATIC, DYNAMIC, BOTH;

    static Provenance of(boolean seenStatically, boolean seenAtRuntime) {
        if (seenStatically && seenAtRuntime) {
            return BOTH;
        }
        return seenAtRuntime ? DYNAMIC : STATIC;
    }
}
