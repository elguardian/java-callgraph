package gr.gousiosg.javacg.rt;

import java.lang.management.ManagementFactory;

import javax.management.ObjectName;

/** MBean implementation; see {@link RecorderControlMXBean}. */
public final class RecorderControl implements RecorderControlMXBean {

    public static final String OBJECT_NAME = "javacg:type=Recorder";

    /**
     * Registers the MBean on the platform MBean server from a daemon thread after
     * {@code delayMillis}. The delay matters on WildFly: touching the platform MBean server
     * before the server has installed its own MBean server builder and log manager breaks boot.
     */
    public static void registerLater(long delayMillis) {
        Thread t = new Thread(() -> {
            try {
                if (delayMillis > 0) {
                    Thread.sleep(delayMillis);
                }
                ObjectName name = new ObjectName(OBJECT_NAME);
                var server = ManagementFactory.getPlatformMBeanServer();
                if (!server.isRegistered(name)) {
                    server.registerMBean(new RecorderControl(), name);
                }
                Recorder.log("MBean registered as " + OBJECT_NAME);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                Recorder.warn("cannot register MBean " + OBJECT_NAME + ": " + e);
            }
        }, "javacg-jmx");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public String start(String label) {
        return Recorder.start(label);
    }

    @Override
    public String stop() {
        return Recorder.stop();
    }

    @Override
    public void dump() {
        Recorder.dump();
    }

    @Override
    public boolean isRecording() {
        return Recorder.isRecording();
    }

    @Override
    public String getLabel() {
        return Recorder.label();
    }

    @Override
    public String getRecordingDirectory() {
        return Recorder.recordingDirectory();
    }

    @Override
    public String getOutputDirectory() {
        return Recorder.outputDirectory();
    }
}
