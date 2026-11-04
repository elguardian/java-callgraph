package gr.gousiosg.javacg.agent;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.jar.JarFile;

/**
 * Agent entry point ({@code Premain-Class} and {@code Agent-Class}).
 *
 * <p>The probe runtime ({@code gr.gousiosg.javacg.rt}) ships as a jar inside the agent jar and is
 * appended to the bootstrap class path, so instrumented classes in any class loader — including
 * JBoss Modules — resolve the same {@code Probe} class. This class must not reference runtime
 * classes: they only become loadable after {@link #appendRuntime}; {@link Installer} does the
 * rest.
 */
public final class AgentMain {

    private static final String RUNTIME_JAR = "/META-INF/javacg/javacg-rt.jar";
    private static final String RUNTIME_CLASS = "gr.gousiosg.javacg.rt.Recorder";

    /** System property holding the options of the loaded agent; also read by the MCP server through Attach. */
    public static final String LOADED_PROPERTY = "javacg.agent.options";

    private AgentMain() {
    }

    public static void premain(String args, Instrumentation inst) {
        boot(args, inst, false);
    }

    public static void agentmain(String args, Instrumentation inst) {
        boot(args, inst, true);
    }

    private static void boot(String args, Instrumentation inst, boolean attached) {
        String loaded = System.getProperty(LOADED_PROPERTY);
        if (loaded != null) {
            // a second transformer would record every call twice
            System.err.println("[javacg] agent already loaded (options: " + loaded + "), ignoring: " + args);
            return;
        }
        System.setProperty(LOADED_PROPERTY, args == null ? "" : args);
        try {
            AgentConfig config = AgentConfig.parse(args);
            if (!runtimeOnBootstrap()) {
                appendRuntime(inst);
            }
            Installer.install(config, inst, attached);
        } catch (Throwable t) {
            System.err.println("[javacg] agent failed to start, the application runs uninstrumented: " + t);
            t.printStackTrace();
        }
    }

    private static boolean runtimeOnBootstrap() {
        try {
            Class.forName(RUNTIME_CLASS, false, null);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static void appendRuntime(Instrumentation inst) throws IOException {
        Path jar = Files.createTempFile("javacg-rt-", ".jar");
        jar.toFile().deleteOnExit();
        try (InputStream in = AgentMain.class.getResourceAsStream(RUNTIME_JAR)) {
            if (in == null) {
                throw new IOException(RUNTIME_JAR + " missing from the agent jar");
            }
            Files.copy(in, jar, StandardCopyOption.REPLACE_EXISTING);
        }
        inst.appendToBootstrapClassLoaderSearch(new JarFile(jar.toFile()));
    }
}
