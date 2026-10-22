package io.github.louch.devdesk.core.testing;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How many tests passed and failed, and which failed, read from a test run's output. Knows the summary lines of
 * Jest, Vitest, pytest, go test, cargo, Gradle and Maven. Anything else: empty, and the exit code tells pass or fail.
 *
 * @param unit "tests", or "packages" for go test (it counts packages unless run with -v)
 * @param failures names of failed tests, as the tool printed them (at most {@link #MAX_FAILURES})
 */
public record TestReport(int passed, int failed, int skipped, String unit, List<String> failures) {

    public static final int MAX_FAILURES = 30;

    private static final Pattern COLOR = Pattern.compile("\u001B\\[[0-9;?]*[A-Za-z]");
    private static final Pattern COUNT = Pattern.compile(
            "(\\d+) (passed|failed|skipped|todo|errors?|xfailed|xpassed|pending)", Pattern.CASE_INSENSITIVE);

    // Summary lines
    private static final Pattern JEST = Pattern.compile("^\\s*Tests:?\\s+(.*\\d.*)$"); // Jest "Tests:", Vitest "Tests"
    private static final Pattern PYTEST = Pattern.compile("^=+ (.*\\d+ \\w+.*) in [\\d.]+s.*=+$");
    private static final Pattern CARGO =
            Pattern.compile("^test result: \\w+\\. (\\d+) passed; (\\d+) failed; (\\d+) ignored");
    private static final Pattern GRADLE =
            Pattern.compile("^(\\d+) tests? completed, (\\d+) failed(?:, (\\d+) skipped)?");
    private static final Pattern MAVEN =
            Pattern.compile("^\\[\\w+\\] Tests run: (\\d+), Failures: (\\d+), Errors: (\\d+), Skipped: (\\d+)\\s*$");
    private static final Pattern GO_OK = Pattern.compile("^ok\\s+\\S+");
    private static final Pattern GO_FAIL = Pattern.compile("^FAIL\\s+\\S+\\s+[\\d.]+s$");

    // Names of failed tests
    private static final List<Pattern> FAILED_NAME = List.of(
            Pattern.compile("^\\s*● (.+?) › (.+)$"), // Jest: "● Suite › test"
            Pattern.compile("^\\s*(?:×|✕|FAIL)\\s+(\\S+\\.(?:test|spec)\\.\\w+ > .+)$"), // Vitest
            Pattern.compile("^FAILED (\\S+)"), // pytest's short summary
            Pattern.compile("^\\s*--- FAIL: (\\S+)"), // go
            Pattern.compile("^test (\\S+) \\.\\.\\. FAILED"), // cargo
            Pattern.compile("^(\\S+ > .+) FAILED$"), // Gradle
            Pattern.compile("^\\[ERROR\\]\\s{2,}(\\w[\\w.$]*\\.\\w+)[:(]")); // Maven's "Results:" list

    public static Optional<TestReport> parse(List<String> output) {
        int passed = 0;
        int failed = 0;
        int skipped = 0;
        boolean found = false;
        boolean packages = false;
        int[] lastMaven = null;
        Set<String> failures = new LinkedHashSet<>();
        int goOk = 0;
        int goFail = 0;
        for (String raw : output) {
            String line = COLOR.matcher(raw).replaceAll("");
            Matcher m;
            if ((m = CARGO.matcher(line)).find()) { // one per test binary: add them up
                passed += Integer.parseInt(m.group(1));
                failed += Integer.parseInt(m.group(2));
                skipped += Integer.parseInt(m.group(3));
                found = true;
            } else if ((m = GRADLE.matcher(line)).find()) {
                int total = Integer.parseInt(m.group(1));
                int f = Integer.parseInt(m.group(2));
                int s = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
                passed += total - f - s;
                failed += f;
                skipped += s;
                found = true;
            } else if ((m = MAVEN.matcher(line)).find()) { // per class, then a total: the last one is the total
                lastMaven = new int[] {
                    Integer.parseInt(m.group(1)),
                    Integer.parseInt(m.group(2)) + Integer.parseInt(m.group(3)),
                    Integer.parseInt(m.group(4))
                };
            } else if ((m = PYTEST.matcher(line)).find() || (m = JEST.matcher(line)).find()) {
                int[] c = counts(m.group(1));
                if (c != null) { // Jest prints "Tests:" once for everything; the last one wins either way
                    passed = c[0];
                    failed = c[1];
                    skipped = c[2];
                    found = true;
                }
            } else if (GO_OK.matcher(line).find()) {
                goOk++;
            } else if (GO_FAIL.matcher(line).find()) {
                goFail++;
            }
            for (Pattern p : FAILED_NAME) {
                if ((m = p.matcher(line)).find() && failures.size() < MAX_FAILURES) {
                    failures.add(m.groupCount() == 2 ? m.group(1) + " › " + m.group(2) : m.group(1));
                    break;
                }
            }
        }
        if (lastMaven != null) {
            passed = lastMaven[0] - lastMaven[1] - lastMaven[2];
            failed = lastMaven[1];
            skipped = lastMaven[2];
            found = true;
        }
        if (!found && goOk + goFail > 0) {
            passed = goOk;
            failed = goFail;
            found = true;
            packages = true;
        }
        if (!found && failures.isEmpty()) {
            return Optional.empty();
        }
        if (!found) {
            failed = failures.size(); // no summary line (a run cut short): at least the names are known
        }
        return Optional.of(
                new TestReport(passed, failed, skipped, packages ? "packages" : "tests", new ArrayList<>(failures)));
    }

    /** "1 failed, 2 skipped, 10 passed, 13 total" → passed, failed, skipped; null if it has no such words. */
    private static int[] counts(String text) {
        Matcher m = COUNT.matcher(text);
        int[] c = new int[3];
        boolean any = false;
        while (m.find()) {
            int n = Integer.parseInt(m.group(1));
            switch (m.group(2).toLowerCase()) {
                case "passed", "xpassed" -> c[0] += n;
                case "failed", "error", "errors" -> c[1] += n;
                default -> c[2] += n; // skipped, todo, pending, xfailed (expected to fail)
            }
            any = true;
        }
        return any ? c : null;
    }
}
