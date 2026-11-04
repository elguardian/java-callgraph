package io.github.elguardian.mcpsettools.devtools.junit;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Reads JUnit XML reports: Maven surefire/failsafe {@code TEST-*.xml}, and the same schema as
 * written by Gradle, Ant and most CI tools. Parsed files are cached by path, size and modification
 * time.
 */
public final class ReportReader {

    /** Report directories found under a project. */
    public static final Set<String> REPORT_DIRS = Set.of("surefire-reports", "failsafe-reports", "test-results");

    private static final Set<String> SKIP_DIRS = Set.of(".git", "node_modules", ".idea", ".mvn", "src", "classes",
            "test-classes", "generated-sources", "generated-test-sources");
    private static final int MAX_TEXT = 256 * 1024;

    private record Key(Path path, long size, long modified) {
    }

    private final Map<Key, List<TestSuite>> cache = new ConcurrentHashMap<>();
    private final XMLInputFactory xml;

    public ReportReader() {
        xml = XMLInputFactory.newFactory();
        xml.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        xml.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        xml.setProperty(XMLInputFactory.IS_COALESCING, true);
    }

    /** Report files under {@code root}: every XML file in a surefire/failsafe/test-results directory. */
    public static List<Path> discover(Path root) throws IOException {
        Set<Path> found = new TreeSet<>();
        Files.walkFileTree(root, Set.of(), 12, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                return SKIP_DIRS.contains(name) && !dir.equals(root) ? FileVisitResult.SKIP_SUBTREE
                        : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (isReport(file) && underReportDir(file)) {
                    found.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return new ArrayList<>(found);
    }

    /** Files or directories; a directory is searched for reports, a file is read as one. */
    public List<TestSuite> read(List<Path> paths) throws IOException {
        List<TestSuite> all = new ArrayList<>();
        for (Path p : paths) {
            if (Files.isDirectory(p)) {
                List<Path> files = discover(p);
                if (files.isEmpty()) {
                    // a report directory itself, or a flat directory of TEST-*.xml
                    try (var s = Files.list(p)) {
                        files = s.filter(ReportReader::isReport).sorted().toList();
                    }
                }
                for (Path f : files) {
                    all.addAll(readFile(f));
                }
            } else if (Files.isRegularFile(p)) {
                all.addAll(readFile(p));
            } else {
                throw new java.nio.file.NoSuchFileException(p.toString());
            }
        }
        return all;
    }

    static boolean isReport(Path f) {
        String n = f.getFileName().toString();
        return n.endsWith(".xml") && !n.equals("failsafe-summary.xml") && !n.startsWith("testng-")
                && (n.startsWith("TEST-") || !n.contains("summary"));
    }

    private static boolean underReportDir(Path f) {
        for (Path p = f.getParent(); p != null; p = p.getParent()) {
            if (p.getFileName() != null && REPORT_DIRS.contains(p.getFileName().toString())) {
                return true;
            }
        }
        return false;
    }

    public List<TestSuite> readFile(Path file) throws IOException {
        BasicFileAttributes a = Files.readAttributes(file, BasicFileAttributes.class);
        Key key = new Key(file.toAbsolutePath().normalize(), a.size(), a.lastModifiedTime().toMillis());
        List<TestSuite> cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        TestSuite.Source source = new TestSuite.Source(key.path(), module(key.path()));
        List<TestSuite> suites;
        try (InputStream in = Files.newInputStream(file)) {
            suites = parse(in, source);
        } catch (XMLStreamException e) {
            throw new IOException("Not a JUnit XML report: " + file + ": " + e.getMessage(), e);
        }
        suites = suites.stream().map(s -> withOutputFile(s, file)).toList();
        cache.keySet().removeIf(k -> k.path().equals(key.path()));
        cache.put(key, suites);
        return suites;
    }

    /** Surefire with redirectTestOutputToFile writes {@code <suite>-output.txt} next to the report. */
    private static TestSuite withOutputFile(TestSuite s, Path report) {
        if (s.systemOut() != null) {
            return s;
        }
        Path out = report.resolveSibling(s.name() + "-output.txt");
        if (!Files.isRegularFile(out)) {
            return s;
        }
        try {
            return new TestSuite(s.source(), s.name(), s.seconds(), s.timestamp(), s.cases(), tail(Files.readString(out,
                    StandardCharsets.UTF_8)), s.systemErr());
        } catch (IOException e) {
            return s;
        }
    }

    /**
     * The module owning a report: the directory above {@code target}/{@code build}, e.g.
     * {@code core} for {@code core/target/surefire-reports/TEST-x.xml}.
     */
    static String module(Path report) {
        for (Path p = report.getParent(); p != null; p = p.getParent()) {
            String n = p.getFileName() == null ? "" : p.getFileName().toString();
            if ((n.equals("target") || n.equals("build")) && p.getParent() != null && p.getParent().getFileName() != null) {
                return p.getParent().getFileName().toString();
            }
        }
        Path dir = report.getParent();
        return dir == null || dir.getFileName() == null ? "" : dir.getFileName().toString();
    }

    private List<TestSuite> parse(InputStream in, TestSuite.Source source) throws XMLStreamException {
        XMLStreamReader r = xml.createXMLStreamReader(in);
        List<TestSuite> suites = new ArrayList<>();
        try {
            while (r.hasNext()) {
                if (r.next() == XMLStreamConstants.START_ELEMENT && r.getLocalName().equals("testsuite")) {
                    suites.add(suite(r, source));
                }
            }
        } finally {
            r.close();
        }
        return suites;
    }

    private static TestSuite suite(XMLStreamReader r, TestSuite.Source source) throws XMLStreamException {
        String name = attr(r, "name");
        String time = attr(r, "time");
        String timestamp = attr(r, "timestamp");
        List<TestCase> cases = new ArrayList<>();
        String out = null;
        String err = null;
        int depth = 1;
        while (depth > 0 && r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                switch (r.getLocalName()) {
                    case "properties" -> skip(r);
                    case "testcase" -> cases.add(testCase(r, source, name));
                    case "system-out" -> out = text(r);
                    case "system-err" -> err = text(r);
                    case "testsuite" -> skip(r);
                    default -> depth++;
                }
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                depth--;
            }
        }
        double seconds = time == null ? cases.stream().mapToDouble(TestCase::seconds).sum() : seconds(time);
        return new TestSuite(source, name == null ? "" : name, seconds, timestamp, cases, out, err);
    }

    private static TestCase testCase(XMLStreamReader r, TestSuite.Source source, String suite) throws XMLStreamException {
        String cls = attr(r, "classname");
        String name = attr(r, "name");
        double seconds = seconds(attr(r, "time"));
        List<TestCase.Problem> problems = new ArrayList<>();
        String out = null;
        String err = null;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.END_ELEMENT && r.getLocalName().equals("testcase")) {
                break;
            }
            if (ev != XMLStreamConstants.START_ELEMENT) {
                continue;
            }
            String el = r.getLocalName();
            TestCase.Problem.Kind kind = switch (el) {
                case "failure" -> TestCase.Problem.Kind.FAILURE;
                case "error" -> TestCase.Problem.Kind.ERROR;
                case "skipped" -> TestCase.Problem.Kind.SKIPPED;
                case "flakyFailure" -> TestCase.Problem.Kind.FLAKY_FAILURE;
                case "flakyError" -> TestCase.Problem.Kind.FLAKY_ERROR;
                case "rerunFailure" -> TestCase.Problem.Kind.RERUN_FAILURE;
                case "rerunError" -> TestCase.Problem.Kind.RERUN_ERROR;
                default -> null;
            };
            if (kind != null) {
                String type = attr(r, "type");
                String message = attr(r, "message");
                problems.add(new TestCase.Problem(kind, type, message, problemText(r, el)));
            } else if (el.equals("system-out")) {
                out = text(r);
            } else if (el.equals("system-err")) {
                err = text(r);
            } else {
                skip(r);
            }
        }
        problems.sort(java.util.Comparator.comparingInt(p -> p.kind().ordinal() == 2 ? 10 : p.kind().ordinal()));
        return new TestCase(source, cls == null || cls.isEmpty() ? suite : cls, name == null ? "" : name, seconds,
                status(problems), problems, out, err);
    }

    /** Text of a problem element; flaky/rerun elements nest stackTrace/system-out children. */
    private static String problemText(XMLStreamReader r, String element) throws XMLStreamException {
        StringBuilder sb = new StringBuilder();
        int depth = 1;
        boolean nested = false;
        while (depth > 0 && r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                depth++;
                nested = true;
                if (!r.getLocalName().equals("stackTrace")) {
                    skip(r);
                    depth--;
                }
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                depth--;
            } else if ((ev == XMLStreamConstants.CHARACTERS || ev == XMLStreamConstants.CDATA) && sb.length() < MAX_TEXT) {
                sb.append(r.getText());
            }
        }
        String s = sb.toString().strip();
        return s.isEmpty() && !nested ? null : s.isEmpty() ? null : s;
    }

    private static TestCase.Status status(List<TestCase.Problem> problems) {
        TestCase.Status status = TestCase.Status.PASSED;
        for (TestCase.Problem p : problems) {
            switch (p.kind()) {
                case FAILURE -> {
                    return TestCase.Status.FAILED;
                }
                case ERROR -> {
                    return TestCase.Status.ERROR;
                }
                case SKIPPED -> status = status == TestCase.Status.PASSED ? TestCase.Status.SKIPPED : status;
                case FLAKY_FAILURE, FLAKY_ERROR -> status = TestCase.Status.FLAKY;
                default -> {
                }
            }
        }
        return status;
    }

    private static String text(XMLStreamReader r) throws XMLStreamException {
        StringBuilder sb = new StringBuilder();
        int depth = 1;
        while (depth > 0 && r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                depth++;
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                depth--;
            } else if (ev == XMLStreamConstants.CHARACTERS || ev == XMLStreamConstants.CDATA) {
                sb.append(r.getText());
                if (sb.length() > 4 * MAX_TEXT) {
                    sb.delete(0, sb.length() - MAX_TEXT);
                }
            }
        }
        String s = sb.toString();
        return s.isBlank() ? null : tail(s);
    }

    /** Keeps the end of long output: the last lines before a failure matter most. */
    private static String tail(String s) {
        return s.length() <= MAX_TEXT ? s : "[... " + (s.length() - MAX_TEXT) + " characters cut]\n"
                + s.substring(s.length() - MAX_TEXT);
    }

    private static void skip(XMLStreamReader r) throws XMLStreamException {
        int depth = 1;
        while (depth > 0 && r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                depth++;
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                depth--;
            }
        }
    }

    private static String attr(XMLStreamReader r, String name) {
        return r.getAttributeValue(null, name);
    }

    private static double seconds(String s) {
        if (s == null || s.isBlank()) {
            return 0;
        }
        try {
            return Double.parseDouble(s.replace(",", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
