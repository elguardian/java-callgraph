package gr.gousiosg.javacg.devtools.junit;

import java.nio.file.Path;
import java.util.List;

/**
 * One {@code testsuite} element.
 *
 * @param seconds   the suite time attribute, or the sum of its test times
 * @param timestamp the {@code timestamp} attribute (Gradle, Ant), or null (surefire 3 omits it)
 */
public record TestSuite(Source source, String name, double seconds, String timestamp, List<TestCase> cases,
        String systemOut, String systemErr) {

    /** Where a suite was read from; {@code module} is the build module owning the report directory. */
    public record Source(Path report, String module) {
    }

    public TestSuite {
        cases = List.copyOf(cases);
    }
}
