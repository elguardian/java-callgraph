package io.github.elguardian.mcpsettools.jvm.threads;

import java.util.List;

/**
 * One {@code Full thread dump}.
 *
 * @param timestamp the date line printed before the header, or null
 * @param jvm       the header's VM description
 * @param deadlocks the cycles the JVM reported itself ({@code Found one Java-level deadlock})
 */
public record ThreadDump(String source, int index, String timestamp, String jvm, List<ThreadInfo> threads,
        List<List<String>> deadlocks) {

    public ThreadDump {
        threads = List.copyOf(threads);
        deadlocks = List.copyOf(deadlocks);
    }

    public ThreadInfo thread(String name) {
        return threads.stream().filter(t -> t.name().equals(name)).findFirst().orElse(null);
    }
}
