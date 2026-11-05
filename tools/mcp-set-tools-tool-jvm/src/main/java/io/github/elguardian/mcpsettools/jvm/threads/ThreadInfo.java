package io.github.elguardian.mcpsettools.jvm.threads;

import java.util.List;

import io.github.elguardian.mcpsettools.commons.stack.StackTrace;

/**
 * One thread of a dump.
 *
 * @param id        the {@code #n} thread number, or -1 (VM threads have none)
 * @param cpuMillis {@code cpu=} (JDK 11+), or -1
 * @param state     {@code java.lang.Thread.State} (RUNNABLE, BLOCKED, ...), or null for VM threads
 * @param detail    the state's detail: {@code on object monitor}, {@code parking}, {@code sleeping}...
 * @param locks     lock lines in stack order
 * @param ownable   {@code Locked ownable synchronizers} (j.u.c locks held)
 */
public record ThreadInfo(String name, long id, boolean daemon, String nid, double cpuMillis, double elapsedSeconds,
        String state, String detail, List<StackTrace.Frame> frames, List<Lock> locks, List<Lock> ownable) {

    /**
     * A {@code - locked <0x..> (a C)} style line.
     *
     * @param frame index of the frame the line follows
     */
    public record Lock(Kind kind, String address, String className, int frame) {

        public enum Kind {
            LOCKED, WAITING_TO_LOCK, WAITING_ON, PARKING, ELIMINATED, OWNABLE
        }

        public String describe() {
            return (className == null ? "?" : className) + (address == null ? "" : "@" + address);
        }
    }

    public ThreadInfo {
        frames = List.copyOf(frames);
        locks = List.copyOf(locks);
        ownable = List.copyOf(ownable);
    }

    /** The lock this thread waits to own: {@code waiting to lock} (monitor) or {@code parking to wait for} (j.u.c). */
    public Lock waitingFor() {
        for (Lock l : locks) {
            if (l.kind() == Lock.Kind.WAITING_TO_LOCK || l.kind() == Lock.Kind.PARKING) {
                return l;
            }
        }
        return null;
    }

    /** Monitors and j.u.c locks this thread owns. */
    public List<Lock> held() {
        List<Lock> h = new java.util.ArrayList<>();
        locks.stream().filter(l -> l.kind() == Lock.Kind.LOCKED).forEach(h::add);
        h.addAll(ownable);
        return h;
    }

    public boolean isJavaThread() {
        return state != null;
    }
}
