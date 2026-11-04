package gr.gousiosg.javacg.devtools.junit;

import java.util.List;

/**
 * One test result.
 *
 * @param className  the test class ({@code classname} attribute; the suite name if absent)
 * @param seconds    run time; with reruns, the time surefire reports
 * @param problems   failure/error first, then flaky and rerun problems, then skipped
 * @param systemOut  captured output of this test, or null
 */
public record TestCase(TestSuite.Source source, String className, String name, double seconds, Status status,
        List<Problem> problems, String systemOut, String systemErr) {

    public enum Status {
        PASSED, FAILED, ERROR, SKIPPED,
        /** Failed, then passed on a rerun. */
        FLAKY
    }

    /** A {@code failure}, {@code error}, {@code skipped} or flaky/rerun element. */
    public record Problem(Kind kind, String type, String message, String stackTrace) {

        public enum Kind {
            FAILURE, ERROR, SKIPPED, FLAKY_FAILURE, FLAKY_ERROR, RERUN_FAILURE, RERUN_ERROR
        }
    }

    public TestCase {
        problems = List.copyOf(problems);
    }

    /** {@code Class#name}. */
    public String id() {
        return className + "#" + name;
    }

    public String module() {
        return source.module();
    }

    public boolean failed() {
        return status == Status.FAILED || status == Status.ERROR;
    }

    /** The failure or error that decided the status; for a flaky test the first flaky problem. */
    public Problem mainProblem() {
        return problems.isEmpty() ? null : problems.get(0);
    }

    /** Number of reruns recorded (flaky or rerun elements). */
    public int reruns() {
        return (int) problems.stream().filter(p -> p.kind().name().startsWith("FLAKY") || p.kind().name().startsWith("RERUN"))
                .count();
    }
}
