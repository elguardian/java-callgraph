package gr.gousiosg.javacg.maven;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

/**
 * Sets a project property (default {@code javacg.argLine}) to the {@code -javaagent:} argument
 * for the javacg agent, to be used in surefire/failsafe {@code argLine} or in an Arquillian
 * container's {@code javaVmArguments}. The jar path alone is in {@code javacg.agentJar}.
 */
@Mojo(name = "prepare-agent", defaultPhase = LifecyclePhase.INITIALIZE, threadSafe = true)
public class PrepareAgentMojo extends AbstractMojo {

    static final String AGENT_KEY = "gr.gousiosg:javacg-agent";
    static final String JBOSS_SYSTEM_PKGS = "org.jboss.byteman,gr.gousiosg.javacg.rt";

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    MavenProject project;

    @Parameter(defaultValue = "${plugin.artifactMap}", readonly = true, required = true)
    Map<String, Artifact> pluginArtifactMap;

    /** Classes to instrument: regexes on binary class names ({@code com\.acme\..*}). */
    @Parameter(required = true)
    List<String> includes;

    /** Classes to leave alone even if included. */
    @Parameter
    List<String> excludes;

    /** Where recordings are written; also the default input of {@code javacg:analyze}. */
    @Parameter(property = "javacg.output", defaultValue = "${project.build.directory}/javacg")
    File outputDirectory;

    /** Delete earlier recordings in {@link #outputDirectory} (only inside the build directory). */
    @Parameter(property = "javacg.clean", defaultValue = "true")
    boolean clean;

    /** {@code startup} (default) or {@code manual} (tests call {@code Recorder.start/stop}). */
    @Parameter(property = "javacg.record", defaultValue = "startup")
    String record;

    /** {@code none}, {@code types} or {@code tostring}. */
    @Parameter(property = "javacg.args", defaultValue = "none")
    String args;

    /** Record lock events for the lock-order detector. */
    @Parameter(property = "javacg.locks", defaultValue = "true")
    boolean locks;

    /** MBean registration: {@code auto}, {@code off} or a delay in ms. */
    @Parameter(property = "javacg.jmx", defaultValue = "off")
    String jmx;

    /** Also add {@code -Djboss.modules.system.pkgs=...} for WildFly / JBoss EAP JVMs. */
    @Parameter(property = "javacg.jbossModules", defaultValue = "false")
    boolean jbossModules;

    /** Property that receives the JVM arguments. */
    @Parameter(property = "javacg.propertyName", defaultValue = "javacg.argLine")
    String propertyName;

    @Parameter(property = "javacg.skip", defaultValue = "false")
    boolean skip;

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            project.getProperties().setProperty(propertyName, "");
            getLog().info("javacg agent skipped");
            return;
        }
        Artifact agent = pluginArtifactMap.get(AGENT_KEY);
        if (agent == null || agent.getFile() == null) {
            throw new MojoExecutionException("Cannot resolve " + AGENT_KEY + " from the plugin's dependencies");
        }
        if (includes == null || includes.isEmpty()) {
            throw new MojoExecutionException("<includes> must name the classes to instrument");
        }
        if (clean) {
            cleanOutput();
        }
        String agentArg = "-javaagent:" + agent.getFile().getAbsolutePath() + "=" + options();
        String value = quoteIfNeeded(agentArg) + (jbossModules ? " -Djboss.modules.system.pkgs=" + JBOSS_SYSTEM_PKGS : "");
        project.getProperties().setProperty(propertyName, value);
        project.getProperties().setProperty("javacg.agentJar", agent.getFile().getAbsolutePath());
        getLog().info(propertyName + " set to " + value);
    }

    String options() {
        List<String> opts = new ArrayList<>();
        opts.add("incl=" + String.join(",", includes));
        if (excludes != null && !excludes.isEmpty()) {
            opts.add("excl=" + String.join(",", excludes));
        }
        opts.add("out=" + outputDirectory.getAbsolutePath());
        opts.add("record=" + record);
        opts.add("args=" + args);
        opts.add("locks=" + locks);
        opts.add("jmx=" + jmx);
        return String.join(";", opts);
    }

    private static String quoteIfNeeded(String arg) {
        return arg.contains(" ") ? "\"" + arg + "\"" : arg;
    }

    private void cleanOutput() throws MojoExecutionException {
        Path out = outputDirectory.toPath().toAbsolutePath().normalize();
        Path build = Path.of(project.getBuild().getDirectory()).toAbsolutePath().normalize();
        if (!out.startsWith(build) || out.equals(build) || !Files.isDirectory(out)) {
            return;
        }
        try (Stream<Path> files = Files.walk(out)) {
            for (Path p : (Iterable<Path>) files.sorted(Comparator.reverseOrder())::iterator) {
                if (!p.equals(out)) {
                    Files.delete(p);
                }
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot clean " + out, e);
        }
    }
}
