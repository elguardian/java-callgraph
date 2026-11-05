package io.github.elguardian.mcpsettools.maven;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import io.github.elguardian.mcpsettools.analyze.Model;
import io.github.elguardian.mcpsettools.analyze.detect.Detector;
import io.github.elguardian.mcpsettools.analyze.detect.Detectors;
import io.github.elguardian.mcpsettools.analyze.detect.Finding;
import io.github.elguardian.mcpsettools.analyze.report.SarifWriter;
import io.github.elguardian.mcpsettools.core.trace.TraceSummary;
import io.github.elguardian.mcpsettools.stat.StaticAnalyzer;
import io.github.elguardian.mcpsettools.stat.StaticResult;

/**
 * Runs the mcp-set-tools detectors over the project's classes (static analysis, with the runtime
 * classpath as libraries) and the recordings left by tests run under the agent, then writes
 * text and SARIF reports.
 */
@Mojo(name = "analyze", defaultPhase = LifecyclePhase.VERIFY, threadSafe = true,
        requiresDependencyResolution = ResolutionScope.RUNTIME)
public class AnalyzeMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    MavenProject project;

    /** Application classes. */
    @Parameter(defaultValue = "${project.build.outputDirectory}")
    File classesDirectory;

    /** Use the runtime classpath for call resolution (bodies are not analyzed). */
    @Parameter(property = "mcpsettools.libraries", defaultValue = "true")
    boolean useLibraries;

    /** Recordings written by the agent ({@code prepare-agent}'s output directory). */
    @Parameter(property = "mcpsettools.output", defaultValue = "${project.build.directory}/mcp-set-tools")
    File recordings;

    /** Detector ids to run; all by default. */
    @Parameter
    List<String> detectors;

    /** Lowest severity reported: {@code high}, {@code medium}, {@code low} or {@code info}. */
    @Parameter(property = "mcpsettools.minSeverity", defaultValue = "low")
    String minSeverity;

    /** Fail the build when a finding at or above this severity exists. */
    @Parameter(property = "mcpsettools.failOn")
    String failOn;

    @Parameter(property = "mcpsettools.sarif", defaultValue = "${project.build.directory}/mcp-set-tools-report.sarif")
    File sarifFile;

    @Parameter(property = "mcpsettools.report", defaultValue = "${project.build.directory}/mcp-set-tools-report.txt")
    File textFile;

    /** Prefix of SARIF source paths, relative to the repository root. */
    @Parameter(property = "mcpsettools.sourceRoot")
    String sourceRoot;

    @Parameter(property = "mcpsettools.skip", defaultValue = "false")
    boolean skip;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("mcp-set-tools analysis skipped");
            return;
        }
        if (classesDirectory == null || !classesDirectory.isDirectory()) {
            getLog().info("No classes in " + classesDirectory + ", nothing to analyze");
            return;
        }
        Finding.Severity min = severity("minSeverity", minSeverity);
        Finding.Severity fail = failOn == null || failOn.isBlank() ? null : severity("failOn", failOn);
        List<Detector> selected;
        try {
            selected = Detectors.select(detectors == null ? List.of() : detectors);
        } catch (IllegalArgumentException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
        Model model = model();
        List<Finding> findings = Detectors.atLeast(Detectors.run(model, selected), min);
        writeReports(model, findings, Detectors.rules(selected));
        summarize(findings);
        if (fail != null) {
            List<Finding> blocking = Detectors.atLeast(findings, fail);
            if (!blocking.isEmpty()) {
                throw new MojoFailureException(blocking.size() + " mcp-set-tools finding(s) at or above " + fail
                        + "; see " + textFile);
            }
        }
    }

    private Model model() throws MojoExecutionException {
        try {
            List<Path> libs = new ArrayList<>();
            if (useLibraries) {
                for (String element : project.getRuntimeClasspathElements()) {
                    Path p = Path.of(element);
                    if (!p.equals(classesDirectory.toPath()) && Files.exists(p)) {
                        libs.add(p);
                    }
                }
            }
            StaticResult statics = StaticAnalyzer.create().app(classesDirectory.toPath()).lib(libs).run();
            statics.warnings().forEach(w -> getLog().debug(w));
            List<TraceSummary> traces = List.of();
            if (recordings != null && recordings.isDirectory()) {
                try {
                    traces = Model.loadRecordings(List.of(recordings.toPath()));
                } catch (java.nio.file.NoSuchFileException e) {
                    getLog().info("No recordings in " + recordings + "; static analysis only");
                }
            } else {
                getLog().info("No recordings directory " + recordings + "; static analysis only");
            }
            getLog().info("mcp-set-tools: " + statics.appClasses().size() + " classes, " + traces.size() + " recording(s)");
            return Model.of(statics, traces);
        } catch (IOException | DependencyResolutionRequiredException e) {
            throw new MojoExecutionException("mcp-set-tools analysis failed: " + e.getMessage(), e);
        }
    }

    private void writeReports(Model model, List<Finding> findings, Map<String, String> rules)
            throws MojoExecutionException {
        String root = sourceRoot;
        if (root == null) {
            root = relativeSourceRoot();
        }
        try {
            write(textFile, w -> Detectors.writeText(findings, w));
            SarifWriter sarif = new SarifWriter(model, root);
            write(sarifFile, w -> sarif.write(findings, rules, w));
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot write mcp-set-tools reports", e);
        }
        getLog().info("mcp-set-tools reports: " + textFile + ", " + sarifFile);
    }

    /** The first compile source root, relative to the project directory. */
    private String relativeSourceRoot() {
        List<String> roots = project.getCompileSourceRoots();
        if (roots == null || roots.isEmpty()) {
            return "";
        }
        Path base = project.getBasedir().toPath().toAbsolutePath();
        Path src = Path.of(roots.get(0)).toAbsolutePath();
        return src.startsWith(base) ? base.relativize(src).toString().replace('\\', '/') : "";
    }

    private void summarize(List<Finding> findings) {
        if (findings.isEmpty()) {
            getLog().info("mcp-set-tools: no findings");
            return;
        }
        for (Finding f : findings) {
            String text = "[" + f.rule() + "] " + f.message()
                    + (f.method() == null ? "" : " at " + f.method().javaName() + (f.line() > 0 ? ":" + f.line() : ""))
                    + (f.confirmedAtRuntime() ? " (confirmed at runtime)" : "");
            switch (f.severity()) {
                case HIGH, MEDIUM -> getLog().warn(text);
                default -> getLog().info(text);
            }
        }
        getLog().info("mcp-set-tools: " + findings.size() + " finding(s), "
                + findings.stream().filter(Finding::confirmedAtRuntime).count() + " confirmed at runtime");
    }

    private static Finding.Severity severity(String name, String value) throws MojoExecutionException {
        try {
            return Finding.Severity.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new MojoExecutionException(name + " must be high, medium, low or info, got " + value);
        }
    }

    private interface Body {
        void write(Writer w) throws IOException;
    }

    private static void write(File file, Body body) throws IOException {
        Files.createDirectories(file.toPath().toAbsolutePath().getParent());
        try (Writer w = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
            body.write(w);
        }
    }
}
