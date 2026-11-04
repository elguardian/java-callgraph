package gr.gousiosg.javacg.rt;

/** JMX view of {@link Recorder}, registered as {@code javacg:type=Recorder}. */
public interface RecorderControlMXBean {

    /** Starts a new recording; returns its directory or null on failure. */
    String start(String label);

    /** Completes the current recording; returns its directory or null. */
    String stop();

    void dump();

    boolean isRecording();

    String getLabel();

    String getRecordingDirectory();

    String getOutputDirectory();

    /** Starts a method watch; returns {@code {"id":...}} or {@code {"error":...}}. See the agent's WatchControl. */
    String watch(String spec);

    /** A watch's results as JSON; {@code stop} ends it and restores the methods. */
    String watchResult(String id, boolean stop);

    String[] getWatches();
}
