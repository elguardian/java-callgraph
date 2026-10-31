package gr.gousiosg.javacg.stat;

/** Call graph construction algorithm for virtual and interface calls. */
public enum Algorithm {
    /**
     * Class hierarchy analysis: a virtual call may reach the implementation in every concrete
     * subtype of the receiver's declared type.
     */
    CHA,
    /**
     * Rapid type analysis, whole-program variant: like CHA but only application classes that are
     * instantiated somewhere in application code ({@code new}) count. Library and JDK classes are
     * assumed instantiated, since their allocation sites are not analyzed.
     */
    RTA
}
