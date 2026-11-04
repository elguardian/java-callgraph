package gr.gousiosg.javacg.rt;

/**
 * Starts and stops method watches; implemented by the agent, which owns the Instrumentation.
 * Results are JSON strings so they cross JMX without custom types.
 */
public interface WatchController {

    /** Starts a watch from a {@code key=value;...} spec; returns {@code {"id":...}} or {@code {"error":...}}. */
    String start(String spec);

    /** The watch's results as JSON; {@code stop} also removes its instrumentation. */
    String result(String id, boolean stop);

    String[] list();
}
