package io.github.elguardian.mcpsettools.build.maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

/**
 * The modules of a Maven build read from the poms, without running Maven: aggregation
 * ({@code modules}, Maven 4 {@code subprojects}, active profiles), coordinates with parent
 * inheritance and property interpolation, dependencies between modules, build order and
 * {@code -pl/-am/-amd} style selections. Profile activation by JDK, OS, property or file is not
 * evaluated: only {@code activeByDefault} and the profiles named by the caller count.
 */
public final class Reactor {

    public record Gav(String groupId, String artifactId, String version) {
        public String key() {
            return groupId + ":" + artifactId;
        }
    }

    /**
     * One module.
     *
     * @param dependsOn keys ({@code groupId:artifactId}) of the reactor modules it needs before it builds:
     *                  its parent, dependencies, plugins, extensions and imported BOMs
     */
    public record Module(Gav gav, String name, String packaging, Path dir, Path pom, List<String> children,
            Set<String> dependsOn) {
    }

    private static final Pattern PROPERTY = Pattern.compile("\\$\\{([^}]+)}");

    private final Path root;
    private final Map<String, Module> byKey = new LinkedHashMap<>();
    private final List<String> warnings = new ArrayList<>();

    private Reactor(Path root) {
        this.root = root;
    }

    /** Reads the reactor rooted at a directory or pom file. */
    public static Reactor load(Path dirOrPom, Collection<String> activeProfiles) throws IOException {
        Path pom = Files.isDirectory(dirOrPom) ? dirOrPom.resolve("pom.xml") : dirOrPom;
        if (!Files.isRegularFile(pom)) {
            throw new java.nio.file.NoSuchFileException(pom.toString(), null, "no pom.xml");
        }
        Reactor r = new Reactor(pom.toAbsolutePath().normalize().getParent());
        Map<Path, Raw> raws = new LinkedHashMap<>();
        r.collect(pom.toAbsolutePath().normalize(), Set.copyOf(activeProfiles), raws, new HashSet<>());
        r.resolve(raws);
        return r;
    }

    public Path root() {
        return root;
    }

    public List<String> warnings() {
        return warnings;
    }

    /** Modules in pom declaration order (aggregators before their modules). */
    public List<Module> modules() {
        return List.copyOf(byKey.values());
    }

    public Optional<Module> module(String key) {
        return Optional.ofNullable(byKey.get(key));
    }

    /**
     * Topological order: every module after the reactor modules it depends on, otherwise in
     * declaration order, as Maven sorts the reactor.
     */
    public List<Module> buildOrder() {
        List<Module> order = new ArrayList<>();
        Set<String> done = new HashSet<>();
        Set<String> visiting = new LinkedHashSet<>();
        for (String k : byKey.keySet()) {
            visit(k, done, visiting, order);
        }
        return order;
    }

    private void visit(String k, Set<String> done, Set<String> visiting, List<Module> order) {
        if (done.contains(k)) {
            return;
        }
        if (!visiting.add(k)) {
            List<String> cycle = new ArrayList<>(visiting);
            cycle = cycle.subList(cycle.indexOf(k), cycle.size());
            throw new IllegalStateException("Dependency cycle in the reactor: " + String.join(" -> ", cycle) + " -> " + k);
        }
        Module m = byKey.get(k);
        for (String d : m.dependsOn()) {
            visit(d, done, visiting, order);
        }
        visiting.remove(k);
        done.add(k);
        order.add(m);
    }

    /**
     * A module by Maven {@code -pl} selector: {@code artifactId}, {@code :artifactId},
     * {@code groupId:artifactId}, or a directory relative to the root.
     */
    public Module find(String selector) {
        String s = selector.strip();
        if (s.contains(":")) {
            String[] parts = s.split(":", -1);
            String g = parts[0];
            String a = parts[1];
            for (Module m : byKey.values()) {
                if (m.gav().artifactId().equals(a) && (g.isEmpty() || m.gav().groupId().equals(g))) {
                    return m;
                }
            }
        }
        Path dir = root.resolve(s).normalize();
        for (Module m : byKey.values()) {
            if (m.dir().equals(dir) || m.gav().artifactId().equals(s)) {
                return m;
            }
        }
        throw new IllegalArgumentException("No module '" + selector + "' in the reactor; modules: "
                + byKey.values().stream().map(m -> m.gav().artifactId()).toList());
    }

    /** Modules the given ones need, transitively (Maven {@code -am}), without the given ones. */
    public Set<Module> upstream(Collection<Module> of) {
        return closure(of, m -> m.dependsOn().stream().map(byKey::get).toList());
    }

    /** Modules that need the given ones, transitively (Maven {@code -amd}), without the given ones. */
    public Set<Module> downstream(Collection<Module> of) {
        Map<String, List<Module>> dependents = new HashMap<>();
        byKey.values().forEach(m -> m.dependsOn().forEach(d -> dependents.computeIfAbsent(d, x -> new ArrayList<>()).add(m)));
        return closure(of, m -> dependents.getOrDefault(m.gav().key(), List.of()));
    }

    private static Set<Module> closure(Collection<Module> start, java.util.function.Function<Module, List<Module>> next) {
        Set<Module> seen = new LinkedHashSet<>();
        Deque<Module> todo = new ArrayDeque<>(start);
        while (!todo.isEmpty()) {
            for (Module n : next.apply(todo.pop())) {
                if (seen.add(n)) {
                    todo.add(n);
                }
            }
        }
        seen.removeAll(start);
        return seen;
    }

    /** The modules a selection builds, in build order. */
    public List<Module> select(Collection<Module> projects, boolean alsoMake, boolean alsoMakeDependents) {
        Set<Module> all = new HashSet<>(projects);
        if (alsoMake) {
            all.addAll(upstream(projects));
        }
        if (alsoMakeDependents) {
            Set<Module> down = downstream(projects);
            all.addAll(down);
            if (alsoMake) {
                all.addAll(upstream(down));
            }
        }
        return buildOrder().stream().filter(all::contains).toList();
    }

    /** The module owning a file: the one whose directory is the longest prefix of its path. */
    public Optional<Module> owner(Path file) {
        Path f = (file.isAbsolute() ? file : root.resolve(file)).normalize();
        Module best = null;
        for (Module m : byKey.values()) {
            if (f.startsWith(m.dir()) && (best == null || m.dir().getNameCount() > best.dir().getNameCount())) {
                best = m;
            }
        }
        return Optional.ofNullable(best);
    }

    /** {@code -pl} value naming modules by path relative to the root ({@code .} for the root). */
    public String plArgument(Collection<Module> modules) {
        return String.join(",", modules.stream().map(m -> {
            String rel = root.relativize(m.dir()).toString();
            return rel.isEmpty() ? "." : rel;
        }).toList());
    }

    // ---- reading

    /** A pom before resolution. */
    private record Raw(Path pom, Element project, List<Element> activeProfiles, List<String> children) {
    }

    private void collect(Path pom, Set<String> profiles, Map<Path, Raw> into, Set<Path> seen) throws IOException {
        if (!seen.add(pom)) {
            return;
        }
        Element project = parse(pom);
        List<Element> active = activeProfiles(project, profiles);
        List<String> children = new ArrayList<>();
        for (Element scope : concat(List.of(project), active)) {
            for (String container : List.of("modules", "subprojects")) {
                Element c = child(scope, container);
                if (c != null) {
                    for (Element m : children(c)) {
                        children.add(m.getTextContent().strip());
                    }
                }
            }
        }
        into.put(pom, new Raw(pom, project, active, children));
        for (String c : children) {
            Path p = pom.getParent().resolve(c).normalize();
            Path childPom = Files.isDirectory(p) ? p.resolve("pom.xml") : p;
            if (Files.isRegularFile(childPom)) {
                collect(childPom, profiles, into, seen);
            } else {
                warnings.add("Module '" + c + "' of " + root.relativize(pom) + " has no pom.xml");
            }
        }
    }

    private void resolve(Map<Path, Raw> raws) {
        // coordinates first: dependencies refer to modules by groupId:artifactId
        Map<Path, Map<String, String>> props = new HashMap<>();
        Map<Path, Gav> gavs = new LinkedHashMap<>();
        for (Raw r : raws.values()) {
            gav(r, raws, props, gavs);
        }
        Set<String> keys = new HashSet<>();
        gavs.values().forEach(g -> keys.add(g.key()));
        for (Raw r : raws.values()) {
            Gav gav = gavs.get(r.pom());
            Map<String, String> p = props.get(r.pom());
            Set<String> deps = new LinkedHashSet<>();
            Element parent = child(r.project(), "parent");
            if (parent != null) {
                String key = interpolate(text(parent, "groupId"), p) + ":" + interpolate(text(parent, "artifactId"), p);
                if (keys.contains(key)) {
                    deps.add(key);
                }
            }
            for (Element scope : concat(List.of(r.project()), r.activeProfiles())) {
                collectRefs(scope, p, keys, gav.groupId(), deps);
            }
            deps.remove(gav.key());
            String packaging = Optional.ofNullable(text(r.project(), "packaging")).map(s -> interpolate(s, p)).orElse("jar");
            String name = Optional.ofNullable(text(r.project(), "name")).map(s -> interpolate(s, p)).orElse(gav.artifactId());
            Module m = new Module(gav, name, packaging, r.pom().getParent(), r.pom(), r.children(), deps);
            if (byKey.put(gav.key(), m) != null) {
                warnings.add("Two modules are " + gav.key());
            }
        }
    }

    private Gav gav(Raw r, Map<Path, Raw> raws, Map<Path, Map<String, String>> props, Map<Path, Gav> gavs) {
        Gav done = gavs.get(r.pom());
        if (done != null) {
            return done;
        }
        Element project = r.project();
        Element parent = child(project, "parent");
        Map<String, String> p = new HashMap<>();
        Gav parentGav = null;
        if (parent != null) {
            Raw parentRaw = parentRaw(r, parent, raws);
            if (parentRaw != null) {
                parentGav = gav(parentRaw, raws, props, gavs);
                p.putAll(props.get(parentRaw.pom()));
            } else {
                parentGav = new Gav(text(parent, "groupId"), text(parent, "artifactId"), text(parent, "version"));
            }
            p.put("project.parent.groupId", nz(parentGav.groupId()));
            p.put("project.parent.version", nz(parentGav.version()));
            p.put("project.parent.artifactId", nz(parentGav.artifactId()));
        }
        for (Element scope : concat(List.of(project), r.activeProfiles())) {
            Element properties = child(scope, "properties");
            if (properties != null) {
                for (Element e : children(properties)) {
                    p.put(e.getTagName(), e.getTextContent().strip());
                }
            }
        }
        String g = text(project, "groupId");
        String v = text(project, "version");
        String a = text(project, "artifactId");
        g = g == null && parentGav != null ? parentGav.groupId() : g;
        v = v == null && parentGav != null ? parentGav.version() : v;
        p.put("project.groupId", nz(g));
        p.put("project.version", nz(v));
        p.put("project.artifactId", nz(a));
        p.put("pom.version", nz(v));
        p.put("version", nz(v));
        p.put("groupId", nz(g));
        Gav gav = new Gav(interpolate(g, p), interpolate(a, p), interpolate(v, p));
        p.put("project.groupId", gav.groupId());
        p.put("project.version", gav.version());
        props.put(r.pom(), p);
        gavs.put(r.pom(), gav);
        return gav;
    }

    /** The parent pom when it is part of the reactor: by relativePath (default ../pom.xml), checked by artifactId. */
    private static Raw parentRaw(Raw r, Element parent, Map<Path, Raw> raws) {
        String rel = text(parent, "relativePath");
        if (rel == null) {
            rel = "../pom.xml";
        }
        if (rel.isEmpty()) {
            return null;
        }
        Path p = r.pom().getParent().resolve(rel).normalize();
        Path pom = Files.isDirectory(p) ? p.resolve("pom.xml") : p;
        Raw candidate = raws.get(pom);
        String artifact = text(parent, "artifactId");
        if (candidate != null && artifact != null && artifact.equals(text(candidate.project(), "artifactId"))) {
            return candidate;
        }
        for (Raw other : raws.values()) {
            if (artifact != null && artifact.equals(text(other.project(), "artifactId")) && other != r) {
                return other;
            }
        }
        return null;
    }

    /** Dependencies, imported BOMs, plugins, plugin dependencies and extensions that are reactor modules. */
    private static void collectRefs(Element scope, Map<String, String> p, Set<String> keys, String ownGroup, Set<String> into) {
        List<Element> refs = new ArrayList<>();
        Element deps = child(scope, "dependencies");
        if (deps != null) {
            refs.addAll(children(deps));
        }
        Element mgmt = child(scope, "dependencyManagement");
        if (mgmt != null && child(mgmt, "dependencies") != null) {
            for (Element d : children(child(mgmt, "dependencies"))) {
                if ("import".equals(text(d, "scope"))) {
                    refs.add(d);
                }
            }
        }
        Element build = child(scope, "build");
        if (build != null) {
            for (String container : List.of("plugins", "extensions")) {
                Element c = child(build, container);
                if (c == null) {
                    continue;
                }
                for (Element plugin : children(c)) {
                    refs.add(plugin);
                    Element pd = child(plugin, "dependencies");
                    if (pd != null) {
                        refs.addAll(children(pd));
                    }
                }
            }
        }
        for (Element d : refs) {
            String g = text(d, "groupId");
            String a = text(d, "artifactId");
            if (a == null) {
                continue;
            }
            String key = interpolate(g == null ? ownGroup : g, p) + ":" + interpolate(a, p);
            if (keys.contains(key)) {
                into.add(key);
            }
        }
    }

    private static List<Element> activeProfiles(Element project, Set<String> requested) {
        List<Element> active = new ArrayList<>();
        Element profiles = child(project, "profiles");
        if (profiles == null) {
            return active;
        }
        for (Element profile : children(profiles)) {
            String id = text(profile, "id");
            if (requested.contains("!" + id)) {
                continue;
            }
            Element activation = child(profile, "activation");
            boolean byDefault = activation != null && "true".equals(text(activation, "activeByDefault"));
            if (requested.contains(id) || byDefault) {
                active.add(profile);
            }
        }
        return active;
    }

    private static String interpolate(String s, Map<String, String> props) {
        if (s == null) {
            return null;
        }
        String result = s;
        for (int round = 0; round < 10 && result.contains("${"); round++) {
            Matcher m = PROPERTY.matcher(result);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String v = props.get(m.group(1));
                if (v == null) {
                    v = System.getProperty(m.group(1));
                }
                m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? m.group() : v));
            }
            m.appendTail(sb);
            if (sb.toString().equals(result)) {
                break;
            }
            result = sb.toString();
        }
        return result;
    }

    private static Element parse(Path pom) throws IOException {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setExpandEntityReferences(false);
            f.setNamespaceAware(false);
            DocumentBuilder b = f.newDocumentBuilder();
            b.setErrorHandler(null);
            Document d = b.parse(pom.toFile());
            return d.getDocumentElement();
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("Cannot read " + pom + ": " + e.getMessage(), e);
        }
    }

    private static Element child(Element parent, String name) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && e.getTagName().equals(name)) {
                return e;
            }
        }
        return null;
    }

    private static List<Element> children(Element parent) {
        List<Element> list = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e) {
                list.add(e);
            }
        }
        return list;
    }

    private static String text(Element parent, String name) {
        Element e = child(parent, name);
        return e == null ? null : e.getTextContent().strip();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static <T> List<T> concat(List<T> a, List<T> b) {
        List<T> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }
}
