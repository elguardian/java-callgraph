package io.github.elguardian.mcpsettools.devtools.threads;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import io.github.elguardian.mcpsettools.commons.text.Templates;
import io.github.elguardian.mcpsettools.commons.stack.StackTrace;
import io.github.elguardian.mcpsettools.commons.stack.StackTraces;

/**
 * What a set of thread dumps says: deadlocks, who blocks whom, threads doing the same thing,
 * pools, and across several dumps the threads that do not move and the ones burning CPU.
 */
public final class ThreadDumpAnalysis {

    /** Threads waiting for a lock, and its owner. */
    public record Contention(String lock, String owner, String ownerState, List<StackTrace.Frame> ownerFrames,
            List<String> waiters, boolean ownerAlsoWaits) {
    }

    /** A cycle of threads each waiting for a lock the next one holds. */
    public record Deadlock(List<String> threads, List<String> locks, boolean reportedByJvm) {
    }

    /** Threads with the same state and top frames. */
    public record Group(String state, List<StackTrace.Frame> frames, List<String> threads, boolean idle) {
    }

    public record Pool(String name, int threads, Map<String, Integer> states) {
    }

    /** A thread in the same state at the same place in every dump. */
    public record Stuck(String thread, String state, List<StackTrace.Frame> frames, int dumps) {
    }

    /** CPU used by a thread between the first and last dump. */
    public record Hot(String thread, double cpuMillis, double percent, String state, StackTrace.Frame top) {
    }

    /** Methods where a thread sits when it has nothing to do: pools, selectors, accept, reference handling, timers. */
    private static final List<String> IDLE = List.of("java.util.concurrent.ThreadPoolExecutor.getTask",
            "java.util.concurrent.LinkedBlockingQueue.take", "java.util.concurrent.LinkedBlockingQueue.poll",
            "java.util.concurrent.SynchronousQueue", "java.util.concurrent.ScheduledThreadPoolExecutor$DelayedWorkQueue.take",
            "java.util.concurrent.ForkJoinPool.awaitWork", "java.lang.ref.Reference.waitForReferencePendingList",
            "java.lang.ref.ReferenceQueue.remove", "sun.nio.ch.EPoll.wait", "sun.nio.ch.EPollArrayWrapper.epollWait",
            "sun.nio.ch.KQueue.poll", "sun.nio.ch.WEPoll.wait", "sun.nio.ch.Net.accept", "java.net.PlainSocketImpl.socketAccept",
            "sun.nio.ch.ServerSocketChannelImpl.accept", "java.util.TimerThread.mainLoop", "jdk.internal.misc.InnocuousThread");

    private final List<ThreadDump> dumps;
    private final ThreadDump last;
    private final List<String> appPackages;

    public ThreadDumpAnalysis(List<ThreadDump> dumps, List<String> appPackages) {
        if (dumps.isEmpty()) {
            throw new IllegalArgumentException("No thread dump found");
        }
        this.dumps = List.copyOf(dumps);
        this.last = dumps.get(dumps.size() - 1);
        this.appPackages = appPackages;
    }

    public List<ThreadDump> dumps() {
        return dumps;
    }

    public ThreadDump last() {
        return last;
    }

    /** Thread states of the last dump; VM threads count as {@code VM}. */
    public Map<String, Integer> states() {
        Map<String, Integer> m = new TreeMap<>();
        last.threads().forEach(t -> m.merge(t.isJavaThread() ? t.state() : "VM", 1, Integer::sum));
        return m;
    }

    /** Locks with waiters, most waiters first (last dump). */
    public List<Contention> contention() {
        Map<String, ThreadInfo> owners = owners(last);
        Map<String, List<String>> waiters = new LinkedHashMap<>();
        Map<String, ThreadInfo.Lock> locks = new HashMap<>();
        for (ThreadInfo t : last.threads()) {
            ThreadInfo.Lock w = t.waitingFor();
            if (w != null && w.address() != null) {
                waiters.computeIfAbsent(w.address(), k -> new ArrayList<>()).add(t.name());
                locks.putIfAbsent(w.address(), w);
            }
        }
        List<Contention> result = new ArrayList<>();
        waiters.forEach((addr, list) -> {
            ThreadInfo owner = owners.get(addr);
            if (owner == null && locks.get(addr).kind() == ThreadInfo.Lock.Kind.PARKING && list.size() < 2) {
                // parked on a condition, a latch or a queue: nobody owns it
                return;
            }
            result.add(new Contention(locks.get(addr).describe(), owner == null ? null : owner.name(),
                    owner == null ? null : owner.state(), owner == null ? List.of() : top(owner.frames(), 6), list,
                    owner != null && owner.waitingFor() != null));
        });
        result.sort(Comparator.comparingInt((Contention c) -> c.waiters().size()).reversed());
        return result;
    }

    /** Deadlocks of the last dump: cycles in the wait-for graph, and the ones the JVM reported. */
    public List<Deadlock> deadlocks() {
        Map<String, ThreadInfo> owners = owners(last);
        Map<String, String> waitsFor = new LinkedHashMap<>();
        Map<String, String> lockOf = new HashMap<>();
        for (ThreadInfo t : last.threads()) {
            ThreadInfo.Lock w = t.waitingFor();
            if (w != null && w.address() != null && owners.containsKey(w.address())) {
                waitsFor.put(t.name(), owners.get(w.address()).name());
                lockOf.put(t.name(), w.describe());
            }
        }
        List<Deadlock> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String start : waitsFor.keySet()) {
            List<String> path = new ArrayList<>();
            String t = start;
            while (t != null && !path.contains(t) && !seen.contains(t)) {
                path.add(t);
                t = waitsFor.get(t);
            }
            if (t != null && path.contains(t)) {
                List<String> cycle = path.subList(path.indexOf(t), path.size());
                Set<String> key = new java.util.TreeSet<>(cycle);
                boolean jvm = last.deadlocks().stream().anyMatch(d -> new java.util.TreeSet<>(d).equals(key));
                result.add(new Deadlock(List.copyOf(cycle), cycle.stream().map(lockOf::get).toList(), jvm));
            }
            seen.addAll(path);
        }
        for (List<String> reported : last.deadlocks()) {
            Set<String> key = new java.util.TreeSet<>(reported);
            if (result.stream().noneMatch(d -> new java.util.TreeSet<>(d.threads()).equals(key))) {
                result.add(new Deadlock(reported, List.of(), true));
            }
        }
        return result;
    }

    /** Java threads grouped by state and top {@code depth} frames, largest groups first. */
    public List<Group> groups(int depth) {
        Map<String, List<ThreadInfo>> groups = new LinkedHashMap<>();
        for (ThreadInfo t : last.threads()) {
            if (!t.isJavaThread()) {
                continue;
            }
            List<StackTrace.Frame> top = top(t.frames(), depth);
            String key = t.state() + "|" + top.stream().map(StackTrace.Frame::location).toList();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(t);
        }
        List<Group> result = new ArrayList<>();
        groups.values().forEach(list -> {
            ThreadInfo first = list.get(0);
            result.add(new Group(first.state(), top(first.frames(), depth), list.stream().map(ThreadInfo::name).toList(),
                    idle(first)));
        });
        result.sort(Comparator.comparingInt((Group g) -> g.threads().size()).reversed());
        return result;
    }

    /** Threads grouped by name with numbers replaced ({@code default task-<*>}). */
    public List<Pool> pools() {
        Map<String, Map<String, Integer>> pools = new LinkedHashMap<>();
        for (ThreadInfo t : last.threads()) {
            String name = Templates.of(t.name()).replace("<*>", "*");
            pools.computeIfAbsent(name, k -> new TreeMap<>()).merge(t.isJavaThread() ? t.state() : "VM", 1, Integer::sum);
        }
        List<Pool> result = new ArrayList<>();
        pools.forEach((n, states) -> result.add(new Pool(n, states.values().stream().mapToInt(Integer::intValue).sum(), states)));
        result.sort(Comparator.comparingInt(Pool::threads).reversed());
        return result;
    }

    /** Busy threads (not idle, not sleeping) in the same state and top frames in every dump (needs two or more dumps). */
    public List<Stuck> stuck(int depth) {
        if (dumps.size() < 2) {
            return List.of();
        }
        List<Stuck> result = new ArrayList<>();
        for (ThreadInfo t : dumps.get(0).threads()) {
            // a thread sleeping on purpose is not stuck
            if (!t.isJavaThread() || idle(t) || t.frames().isEmpty() || "sleeping".equals(t.detail())) {
                continue;
            }
            List<String> top = top(t.frames(), depth).stream().map(StackTrace.Frame::location).toList();
            boolean same = true;
            for (ThreadDump d : dumps.subList(1, dumps.size())) {
                ThreadInfo o = find(d, t);
                if (o == null || !t.state().equals(o.state())
                        || !top.equals(top(o.frames(), depth).stream().map(StackTrace.Frame::location).toList())) {
                    same = false;
                    break;
                }
            }
            if (same) {
                result.add(new Stuck(t.name(), t.state(), top(t.frames(), depth), dumps.size()));
            }
        }
        return result;
    }

    /** Threads by CPU used between the first and the last dump (JDK 11+ dumps carry cpu=). */
    public List<Hot> hot(int limit) {
        if (dumps.size() < 2) {
            return List.of();
        }
        ThreadDump first = dumps.get(0);
        double wall = wallMillis(first, last);
        List<Hot> result = new ArrayList<>();
        for (ThreadInfo t : last.threads()) {
            ThreadInfo before = find(first, t);
            if (before == null || t.cpuMillis() < 0 || before.cpuMillis() < 0) {
                continue;
            }
            double delta = t.cpuMillis() - before.cpuMillis();
            if (delta > 0) {
                result.add(new Hot(t.name(), delta, wall > 0 ? 100 * delta / wall : -1, t.state(),
                        t.frames().isEmpty() ? null : t.frames().get(0)));
            }
        }
        result.sort(Comparator.comparingDouble(Hot::cpuMillis).reversed());
        return result.size() > limit ? result.subList(0, limit) : result;
    }

    /** Wall time between dumps: from the elapsed= of a thread present in both, else from the timestamps. */
    private static double wallMillis(ThreadDump a, ThreadDump b) {
        for (ThreadInfo t : b.threads()) {
            ThreadInfo o = find(a, t);
            if (o != null && t.elapsedSeconds() > 0 && o.elapsedSeconds() > 0) {
                return (t.elapsedSeconds() - o.elapsedSeconds()) * 1000;
            }
        }
        return -1;
    }

    /** The same thread in another dump: by thread number, else by name. */
    private static ThreadInfo find(ThreadDump d, ThreadInfo t) {
        for (ThreadInfo o : d.threads()) {
            if (t.id() >= 0 && o.id() == t.id() && o.name().equals(t.name()) || t.id() < 0 && o.name().equals(t.name())) {
                return o;
            }
        }
        return null;
    }

    /** Owner of each lock address: monitors from {@code - locked}, j.u.c locks from ownable synchronizers. */
    private static Map<String, ThreadInfo> owners(ThreadDump d) {
        Map<String, ThreadInfo> owners = new HashMap<>();
        for (ThreadInfo t : d.threads()) {
            for (ThreadInfo.Lock l : t.held()) {
                if (l.address() != null) {
                    owners.putIfAbsent(l.address(), t);
                }
            }
        }
        return owners;
    }

    /**
     * Whether a thread is waiting for work rather than for something: it sits in a pool, selector,
     * accept or reference-handler method; it has no Java frames (JVM service threads); or it is parked, waiting or sleeping and, when the
     * application packages are known, no application code is on its top frames. BLOCKED threads
     * are never idle.
     */
    boolean idle(ThreadInfo t) {
        if ("BLOCKED".equals(t.state())) {
            return false;
        }
        if (t.frames().isEmpty()) {
            // JVM service threads (Signal Dispatcher, compiler threads, ...)
            return true;
        }
        List<StackTrace.Frame> top = top(t.frames(), 12);
        if (top.stream().anyMatch(f -> IDLE.stream().anyMatch(f.location()::startsWith))) {
            return true;
        }
        boolean waiting = "WAITING".equals(t.state()) || "TIMED_WAITING".equals(t.state());
        return waiting && !appPackages.isEmpty()
                && top.stream().noneMatch(f -> StackTraces.kind(f, appPackages) == StackTraces.Kind.APP);
    }

    private static List<StackTrace.Frame> top(List<StackTrace.Frame> frames, int n) {
        return frames.subList(0, Math.min(n, frames.size()));
    }
}
