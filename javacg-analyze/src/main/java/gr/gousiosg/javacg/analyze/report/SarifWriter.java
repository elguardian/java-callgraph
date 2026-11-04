package gr.gousiosg.javacg.analyze.report;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import gr.gousiosg.javacg.analyze.Model;
import gr.gousiosg.javacg.analyze.detect.Finding;
import gr.gousiosg.javacg.core.MethodId;
import gr.gousiosg.javacg.commons.json.JsonWriter;
import gr.gousiosg.javacg.stat.ClassInfo;

/**
 * Writes findings as SARIF 2.1.0, for IDEs, GitHub code scanning and CI dashboards.
 *
 * <ul>
 * <li>{@code level}: HIGH → error, MEDIUM → warning, LOW/INFO → note.</li>
 * <li>Locations are source files relative to {@code SRCROOT} ({@code pkg/dir/File.java},
 * optionally under a source root such as {@code src/main/java}), plus the method as a
 * logical location.</li>
 * <li>Related locations carry the secondary positions (null check, lock acquisitions, ...).</li>
 * <li>{@code properties} hold javacg's severity, confidence and runtime evidence.</li>
 * <li>{@code partialFingerprints} ignore line numbers, so results survive unrelated edits.</li>
 * </ul>
 */
public final class SarifWriter {

    private final Model model;
    private final String sourceRoot;

    /**
     * @param sourceRoot prefix of source paths relative to the repository, e.g.
     *                   {@code src/main/java}; empty for none
     */
    public SarifWriter(Model model, String sourceRoot) {
        this.model = model;
        String root = sourceRoot == null ? "" : sourceRoot.replace('\\', '/');
        this.sourceRoot = root.isEmpty() || root.endsWith("/") ? root : root + "/";
    }

    public void write(List<Finding> findings, Map<String, String> rules, Writer out) {
        List<String> ruleIds = new ArrayList<>(rules.keySet());
        JsonWriter json = new JsonWriter(out);
        json.beginObject()
                .field("$schema", "https://json.schemastore.org/sarif-2.1.0.json")
                .field("version", "2.1.0");
        json.name("runs").beginArray().beginObject();

        json.name("tool").beginObject().name("driver").beginObject()
                .field("name", "javacg")
                .field("version", version())
                .field("informationUri", "https://github.com/elguardian/java-callgraph");
        json.name("rules").beginArray();
        for (String id : ruleIds) {
            json.beginObject().field("id", id);
            json.name("shortDescription").beginObject().field("text", rules.get(id)).endObject();
            json.endObject();
        }
        json.endArray().endObject().endObject();

        json.name("originalUriBaseIds").beginObject().name("SRCROOT").beginObject()
                .field("uri", "file:///").endObject().endObject();

        json.name("results").beginArray();
        for (Finding f : findings) {
            json.beginObject().field("ruleId", f.rule());
            if (ruleIds.contains(f.rule())) {
                json.field("ruleIndex", ruleIds.indexOf(f.rule()));
            }
            json.field("level", level(f.severity()));
            json.name("message").beginObject().field("text", f.message()).endObject();
            json.name("locations").beginArray();
            if (f.method() != null) {
                location(json, f.method(), f.line(), null, -1);
            }
            json.endArray();
            if (!f.related().isEmpty()) {
                json.name("relatedLocations").beginArray();
                int id = 0;
                for (Finding.Location l : f.related()) {
                    if (l.method() != null) {
                        location(json, l.method(), l.line(), l.note(), id++);
                    }
                }
                json.endArray();
            }
            json.name("partialFingerprints").beginObject()
                    .field("javacg/v1", fingerprint(f)).endObject();
            json.name("properties").beginObject()
                    .field("severity", f.severity().name())
                    .field("confidence", f.confidence().name())
                    .field("confirmedAtRuntime", f.confirmedAtRuntime());
            if (!f.evidence().isEmpty()) {
                json.name("runtimeEvidence").beginArray();
                f.evidence().forEach(json::value);
                json.endArray();
            }
            json.endObject();
            json.endObject();
        }
        json.endArray();

        json.endObject().endArray().endObject();
        json.flush();
    }

    private void location(JsonWriter json, MethodId m, int line, String message, int id) {
        json.beginObject();
        if (id >= 0) {
            json.field("id", id);
        }
        if (message != null) {
            json.name("message").beginObject().field("text", message).endObject();
        }
        json.name("physicalLocation").beginObject();
        json.name("artifactLocation").beginObject().field("uri", sourcePath(m)).field("uriBaseId", "SRCROOT")
                .endObject();
        if (line > 0) {
            json.name("region").beginObject().field("startLine", line).endObject();
        }
        json.endObject();
        json.name("logicalLocations").beginArray().beginObject()
                .field("fullyQualifiedName", m.javaName()).field("kind", "function").endObject().endArray();
        json.endObject();
    }

    /** Source file of a method's class: package directory + SourceFile attribute (or a guess). */
    String sourcePath(MethodId m) {
        String owner = m.owner();
        int slash = owner.lastIndexOf('/');
        String pkg = slash < 0 ? "" : owner.substring(0, slash + 1);
        String file = null;
        if (model.hasStatic()) {
            ClassInfo c = model.statics().hierarchy().get(owner);
            file = c == null ? null : c.sourceFile();
        }
        if (file == null) {
            String simple = owner.substring(slash + 1);
            int dollar = simple.indexOf('$');
            file = (dollar > 0 ? simple.substring(0, dollar) : simple) + ".java";
        }
        return sourceRoot + pkg + file;
    }

    private static String level(Finding.Severity s) {
        return switch (s) {
            case HIGH -> "error";
            case MEDIUM -> "warning";
            case LOW, INFO -> "note";
        };
    }

    private static String fingerprint(Finding f) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String key = f.rule() + "|" + (f.method() == null ? "" : f.method()) + "|" + f.message();
            return HexFormat.of().formatHex(md.digest(key.getBytes(StandardCharsets.UTF_8))).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The javacg version, from the build. */
    public static String version() {
        try (InputStream in = SarifWriter.class.getResourceAsStream("/gr/gousiosg/javacg/analyze/version.properties")) {
            Properties p = new Properties();
            if (in != null) {
                p.load(in);
            }
            return p.getProperty("version", "unknown");
        } catch (IOException e) {
            return "unknown";
        }
    }

    /** Groups findings by rule id, for summaries. */
    public static Map<String, Integer> countByRule(List<Finding> findings) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        findings.forEach(f -> counts.merge(f.rule(), 1, Integer::sum));
        return counts;
    }
}
