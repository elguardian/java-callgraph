package io.github.elguardian.mcpsettools.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Agent options, given as {@code -javaagent:mcp-set-tools-agent.jar=key=value;key=value;...}.
 *
 * <pre>
 * incl=re1,re2     classes to instrument (regex on the binary name, must match fully)
 * excl=re1,re2     classes to skip even if included
 * out=dir          base output directory (default ${java.io.tmpdir}/mcp-set-tools/&lt;pid&gt;)
 * label=name       label of the recording started at startup (default "default")
 * record=startup   start recording immediately (default); "manual" waits for Recorder.start
 * args=none        argument capture: none (default), types, tostring
 * locks=false      record monitor and java.util.concurrent Lock acquire/release events
 * jmx=auto         MBean registration delay in ms, "off", or "auto" (20000 on WildFly, else 0)
 * verbose=false    log every instrumented class
 * </pre>
 *
 * {@code out} and {@code label} may reference system properties as {@code %{name}} or
 * {@code %{name:default}}, e.g. {@code label=%{jboss.node.name:client}}, so one shared argument
 * line gives each JVM of a cluster its own recording. ({@code ${...}} is not used because
 * Maven and Arquillian would substitute it first.)
 *
 * Invalid patterns and unknown keys are reported and ignored; they never stop the JVM.
 */
final class AgentConfig {

    /** Never instrumented: the JDK, and the agent and runtime themselves. */
    private static final List<String> ALWAYS_EXCLUDED = List.of("java.", "javax.management.", "jdk.", "sun.",
            "com.sun.", "io.github.elguardian.mcpsettools.");

    static final long JMX_OFF = -1;
    static final long WILDFLY_JMX_DELAY = 20_000;

    final List<Pattern> include = new ArrayList<>();
    final List<Pattern> exclude = new ArrayList<>();
    final List<String> warnings = new ArrayList<>();
    String out;
    String label = "default";
    boolean recordAtStartup = true;
    String args = "none";
    boolean locks;
    long jmxDelay;
    boolean verbose;

    static AgentConfig parse(String argument) {
        AgentConfig c = new AgentConfig();
        String jmx = "auto";
        if (argument == null || argument.isBlank()) {
            c.warnings.add("no incl= patterns given, nothing will be instrumented");
            argument = "";
        }
        for (String token : argument.split(";")) {
            token = token.trim();
            if (token.isEmpty()) {
                continue;
            }
            int eq = token.indexOf('=');
            if (eq <= 0) {
                c.warnings.add("ignoring option without '=': " + token);
                continue;
            }
            String key = token.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String value = token.substring(eq + 1).trim();
            switch (key) {
                case "incl" -> c.patterns(value, c.include);
                case "excl" -> c.patterns(value, c.exclude);
                case "out" -> c.out = expand(value);
                case "label" -> c.label = expand(value);
                case "record" -> {
                    if (value.equalsIgnoreCase("manual")) {
                        c.recordAtStartup = false;
                    } else if (!value.equalsIgnoreCase("startup")) {
                        c.warnings.add("record must be startup or manual, got " + value);
                    }
                }
                case "args" -> {
                    String v = value.toLowerCase(Locale.ROOT);
                    if (v.equals("none") || v.equals("types") || v.equals("tostring")) {
                        c.args = v;
                    } else {
                        c.warnings.add("args must be none, types or tostring, got " + value);
                    }
                }
                case "locks" -> c.locks = Boolean.parseBoolean(value);
                case "jmx" -> jmx = value;
                case "verbose" -> c.verbose = Boolean.parseBoolean(value);
                default -> c.warnings.add("unknown option " + key);
            }
        }
        if (c.include.isEmpty() && !argument.isEmpty()) {
            c.warnings.add("no valid incl= patterns, nothing will be instrumented");
        }
        c.jmxDelay = jmxDelay(jmx, c.warnings);
        return c;
    }

    boolean shouldInstrument(String className) {
        for (String prefix : ALWAYS_EXCLUDED) {
            if (className.startsWith(prefix)) {
                return false;
            }
        }
        boolean included = false;
        for (Pattern p : include) {
            if (p.matcher(className).matches()) {
                included = true;
                break;
            }
        }
        if (!included) {
            return false;
        }
        for (Pattern p : exclude) {
            if (p.matcher(className).matches()) {
                return false;
            }
        }
        return true;
    }

    private static final Pattern PROPERTY = Pattern.compile("%\\{([^}:]+)(?::([^}]*))?}");

    /** Replaces {@code %{name}} / {@code %{name:default}} with system property values. */
    static String expand(String value) {
        java.util.regex.Matcher m = PROPERTY.matcher(value);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = System.getProperty(m.group(1));
            if (v == null) {
                v = m.group(2) != null ? m.group(2) : "";
            }
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(v));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private void patterns(String value, List<Pattern> target) {
        for (String p : value.split(",")) {
            p = p.trim();
            if (p.isEmpty()) {
                continue;
            }
            try {
                target.add(Pattern.compile(p));
            } catch (PatternSyntaxException e) {
                warnings.add("ignoring invalid pattern '" + p + "': " + e.getDescription());
            }
        }
    }

    private static long jmxDelay(String value, List<String> warnings) {
        if (value.equalsIgnoreCase("off")) {
            return JMX_OFF;
        }
        if (value.equalsIgnoreCase("auto")) {
            return onWildFly() ? WILDFLY_JMX_DELAY : 0;
        }
        try {
            return Math.max(0, Long.parseLong(value));
        } catch (NumberFormatException e) {
            warnings.add("jmx must be auto, off or a delay in ms, got " + value);
            return onWildFly() ? WILDFLY_JMX_DELAY : 0;
        }
    }

    private static boolean onWildFly() {
        return System.getProperty("jboss.modules.system.pkgs") != null
                || System.getProperty("sun.java.command", "").contains("jboss-modules");
    }
}
