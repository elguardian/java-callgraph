package gr.gousiosg.javacg.devtools.timeline;

/** A moment on one thread: a log line matching a marker, an exception. */
public record Mark(String process, String thread, long time, String label, String category) {

    public String lane() {
        return process + " / " + thread;
    }
}
