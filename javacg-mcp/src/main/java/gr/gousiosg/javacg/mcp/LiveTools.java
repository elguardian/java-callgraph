package gr.gousiosg.javacg.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.management.JMX;
import javax.management.MBeanServerConnection;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

import com.sun.tools.attach.AgentInitializationException;
import com.sun.tools.attach.AgentLoadException;
import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.VirtualMachine;

import gr.gousiosg.javacg.rt.RecorderControl;
import gr.gousiosg.javacg.rt.RecorderControlMXBean;

/**
 * Tools acting on running JVMs: list local JVMs, load the agent into one (Attach API), and drive
 * its recordings through the {@code javacg:type=Recorder} MBean, locally by pid or remotely by JMX URL.
 */
final class LiveTools {

    /** Set by the agent when it loads; see {@code AgentMain.LOADED_PROPERTY}. */
    static final String AGENT_LOADED_PROPERTY = "javacg.agent.options";

    private static final String EMBEDDED_AGENT = "/META-INF/javacg/javacg-agent.jar";
    private static final String LOCAL_CONNECTOR = "com.sun.management.jmxremote.localConnectorAddress";

    private enum Action {
        STATUS, START, STOP, DUMP
    }

    List<ToolDef> tools() {
        return List.of(listJvms(), attachAgent(), recorder());
    }

    private ToolDef listJvms() {
        return new ToolDef("list_jvms", "List local JVMs", """
                Lists the JVMs on this machine that the server can attach to (same user, attach not \
                disabled), with their main class or jar and arguments, and whether the javacg agent is \
                loaded in them. A WildFly server shows as jboss-modules.jar.""", true,
                Schema.object().string("match", "Only JVMs whose description contains this text", false).build(),
                args -> {
                    String match = args.string("match", null);
                    String self = Long.toString(ProcessHandle.current().pid());
                    List<Map<String, Object>> jvms = VirtualMachine.list().stream()
                            .filter(d -> !d.id().equals(self))
                            .filter(d -> match == null || d.displayName().toLowerCase(Locale.ROOT)
                                    .contains(match.toLowerCase(Locale.ROOT)))
                            .map(d -> {
                                Map<String, Object> m = new LinkedHashMap<>();
                                m.put("pid", d.id());
                                m.put("name", d.displayName());
                                agentOptions(d.id(), m);
                                return m;
                            }).toList();
                    return Map.of("jvms", jvms);
                });
    }

    private ToolDef attachAgent() {
        return new ToolDef("attach_agent", "Attach the javacg agent", """
                Loads the javacg agent into a running local JVM (Attach API). Classes matching incl are \
                instrumented, including already loaded ones; methods executing at that moment keep running \
                uninstrumented. Options are the agent's key=value list separated by ';', e.g. \
                'incl=com\\.acme\\..*;out=/tmp/javacg;record=manual': incl/excl (class regexes matching the \
                whole binary name, comma-separated), out (base directory, one subdirectory per recording), \
                label, record=startup|manual, args=none|types|tostring, locks=true|false (needed for \
                runtime lock-order findings), jmx=auto|off|<ms>. Keep jmx enabled to use the recorder tool. \
                The agent can be loaded once per JVM. On WildFly, gr.gousiosg.javacg.rt must be in \
                jboss.modules.system.pkgs at server start or instrumented module classes fail to link.""",
                false,
                Schema.object().string("pid", "Process id of the target JVM (see list_jvms)", true)
                        .string("options", "Agent options, e.g. 'incl=com\\.acme\\..*;out=/tmp/javacg;locks=true'",
                                true)
                        .string("agent_jar", "Agent jar to load (default: the one embedded in this server, or "
                                + "$JAVACG_AGENT_JAR)", false).build(),
                args -> {
                    String pid = args.require("pid");
                    String options = args.require("options");
                    Path jar = agentJar(args);
                    VirtualMachine vm = attach(pid);
                    try {
                        String loaded = vm.getSystemProperties().getProperty(AGENT_LOADED_PROPERTY);
                        if (loaded != null) {
                            throw new ToolException("The javacg agent is already loaded in " + pid
                                    + " with options '" + loaded + "'; it can be loaded once per JVM. Use the "
                                    + "recorder tool to start/stop recordings");
                        }
                        vm.loadAgent(jar.toString(), options);
                    } catch (AgentInitializationException e) {
                        throw new ToolException("The agent failed to start in " + pid + " (see the target's "
                                + "stderr): " + e.getMessage(), e);
                    } catch (AgentLoadException e) {
                        throw new ToolException("Cannot load " + jar + " into " + pid + ": " + e.getMessage(), e);
                    } finally {
                        vm.detach();
                    }
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("pid", pid);
                    m.put("agent_jar", jar.toString());
                    m.put("options", options);
                    m.put("note", "The agent reports on the target's stderr. With record=manual nothing is "
                            + "recorded until recorder start; with jmx=auto the MBean appears after 20 s on "
                            + "WildFly, immediately elsewhere.");
                    return m;
                });
    }

    private ToolDef recorder() {
        return new ToolDef("recorder", "Control recordings", """
                Controls the javacg agent's recorder in a running JVM through its javacg:type=Recorder \
                MBean. status: whether it is recording, the label and directories. start: completes the \
                current recording and starts a new one named by label (e.g. the test about to run). stop: \
                completes the current recording and returns its directory, which load_model accepts as a \
                trace input (the directory is on the target's file system). dump: flushes buffered events \
                without stopping. Target a local JVM by pid, or a remote one by jmx_url \
                (service:jmx:rmi:///jndi/rmi://host:port/jmxrmi, or host:port for that form).""", false,
                Schema.object()
                        .choice("action", "What to do", null, "status", "start", "stop", "dump")
                        .string("pid", "Local target JVM", false)
                        .string("jmx_url", "Remote target: JMX service URL or host:port", false)
                        .string("username", "JMX user for jmx_url", false)
                        .string("password", "JMX password for jmx_url", false)
                        .string("label", "Name of the recording to start (default: mcp-<time>)", false).build(),
                args -> {
                    Action action = args.choice("action", Action.class, null);
                    try (JMXConnector connector = connect(args)) {
                        MBeanServerConnection conn = connector.getMBeanServerConnection();
                        ObjectName name = recorderName();
                        if (!conn.isRegistered(name)) {
                            throw new ToolException("No " + RecorderControl.OBJECT_NAME + " MBean in the target: "
                                    + "the javacg agent is not loaded (attach_agent), was started with jmx=off, "
                                    + "or is still waiting for its jmx delay (20 s on WildFly with jmx=auto)");
                        }
                        RecorderControlMXBean rec = JMX.newMXBeanProxy(conn, name, RecorderControlMXBean.class);
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("action", Views.lower(action));
                        switch (action) {
                            case START -> {
                                String label = args.string("label", "mcp-" + System.currentTimeMillis());
                                String dir = rec.start(label);
                                if (dir == null) {
                                    throw new ToolException("The recorder could not start '" + label
                                            + "' (see the target's stderr)");
                                }
                                m.put("started", dir);
                            }
                            case STOP -> {
                                String dir = rec.stop();
                                m.put("stopped", dir);
                                m.put("note", dir == null ? "Nothing was recording"
                                        : "Pass this directory to load_model as trace (with the application as "
                                                + "app for the merged view)");
                            }
                            case DUMP -> rec.dump();
                            case STATUS -> {
                            }
                        }
                        m.put("recording", rec.isRecording());
                        m.put("label", rec.getLabel());
                        m.put("recording_directory", rec.getRecordingDirectory());
                        m.put("output_directory", rec.getOutputDirectory());
                        return m;
                    }
                });
    }

    private static JMXConnector connect(ToolArgs args) throws IOException {
        boolean byPid = args.has("pid");
        boolean byUrl = args.has("jmx_url");
        if (byPid == byUrl) {
            throw new ToolException("Give either 'pid' (local JVM) or 'jmx_url' (remote JVM)");
        }
        if (byPid) {
            String pid = args.require("pid");
            VirtualMachine vm = attach(pid);
            String address;
            try {
                address = vm.getAgentProperties().getProperty(LOCAL_CONNECTOR);
                if (address == null) {
                    address = vm.startLocalManagementAgent();
                }
            } finally {
                vm.detach();
            }
            return JMXConnectorFactory.connect(new JMXServiceURL(address));
        }
        String url = args.require("jmx_url");
        if (!url.startsWith("service:jmx:")) {
            url = "service:jmx:rmi:///jndi/rmi://" + url + "/jmxrmi";
        }
        Map<String, Object> env = new HashMap<>();
        if (args.has("username")) {
            env.put(JMXConnector.CREDENTIALS, new String[] { args.require("username"), args.string("password", "") });
        }
        try {
            return JMXConnectorFactory.connect(new JMXServiceURL(url), env);
        } catch (java.net.MalformedURLException e) {
            throw new ToolException("Bad jmx_url " + url + ": " + e.getMessage());
        } catch (IOException e) {
            throw new ToolException("Cannot connect to " + url + ": " + e.getMessage()
                    + (url.contains("remote+http") ? " (WildFly's remote+http JMX protocol needs jboss-client.jar "
                            + "on the server's class path; use a pid or an RMI connector instead)" : ""), e);
        }
    }

    private static VirtualMachine attach(String pid) throws IOException {
        try {
            return VirtualMachine.attach(pid);
        } catch (AttachNotSupportedException e) {
            throw new ToolException("Cannot attach to " + pid + ": " + e.getMessage()
                    + " (is it a running JVM of the same user? see list_jvms)", e);
        }
    }

    /** Adds {@code javacg_agent} (the agent's options) when it is loaded; skipped silently if attach fails. */
    private static void agentOptions(String pid, Map<String, Object> into) {
        try {
            VirtualMachine vm = VirtualMachine.attach(pid);
            try {
                String options = vm.getSystemProperties().getProperty(AGENT_LOADED_PROPERTY);
                into.put("javacg_agent", options != null);
                if (options != null) {
                    into.put("agent_options", options);
                }
            } finally {
                vm.detach();
            }
        } catch (Exception e) {
            into.put("attachable", false);
        }
    }

    private static ObjectName recorderName() {
        try {
            return new ObjectName(RecorderControl.OBJECT_NAME);
        } catch (MalformedObjectNameException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The agent jar: the argument, $JAVACG_AGENT_JAR, -Djavacg.agent.jar, or the copy embedded in this jar. */
    static Path agentJar(ToolArgs args) throws IOException {
        String explicit = args.string("agent_jar", System.getenv("JAVACG_AGENT_JAR"));
        if (explicit == null) {
            explicit = System.getProperty("javacg.agent.jar");
        }
        if (explicit != null) {
            Path p = ToolArgs.path(explicit);
            if (!Files.isRegularFile(p)) {
                throw new ToolException("Agent jar does not exist: " + p);
            }
            return p;
        }
        return extractEmbeddedAgent();
    }

    /**
     * Copies the embedded agent to a content-addressed file under the temp directory. It is never
     * deleted: target JVMs keep reading classes from it after this server exits.
     */
    private static synchronized Path extractEmbeddedAgent() throws IOException {
        byte[] bytes;
        try (InputStream in = LiveTools.class.getResourceAsStream(EMBEDDED_AGENT)) {
            if (in == null) {
                throw new ToolException("No agent jar embedded in this server (built without packaging?): pass "
                        + "agent_jar or set JAVACG_AGENT_JAR");
            }
            bytes = in.readAllBytes();
        }
        String hash;
        try {
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "javacg-mcp");
        Path jar = dir.resolve("javacg-agent-" + hash + ".jar");
        if (!Files.isRegularFile(jar) || Files.size(jar) != bytes.length) {
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, "agent-", ".tmp");
            Files.write(tmp, bytes);
            Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        return jar;
    }
}
