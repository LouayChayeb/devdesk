package io.github.louch.devdesk.core.testing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class TestReportTest {

    @Test
    void jest() {
        var r = TestReport.parse(List.of(
                        "  ● cart › adds an item",
                        "    expect(received).toBe(expected)",
                        "Test Suites: 1 failed, 3 passed, 4 total",
                        "Tests:       1 failed, 2 skipped, 10 passed, 13 total"))
                .orElseThrow();
        assertThat(r).isEqualTo(new TestReport(10, 1, 2, "tests", List.of("cart › adds an item")));
    }

    @Test
    void vitestWithColors() {
        var r = TestReport.parse(
                        List.of(
                                " \u001B[31m×\u001B[39m src/cart.test.ts > cart > adds an item",
                                "\u001B[2m      Tests \u001B[22m \u001B[1m\u001B[31m1 failed\u001B[39m\u001B[22m\u001B[2m | \u001B[22m\u001B[1m\u001B[32m4 passed\u001B[39m (5)"))
                .orElseThrow();
        assertThat(r.passed()).isEqualTo(4);
        assertThat(r.failed()).isEqualTo(1);
        assertThat(r.failures()).containsExactly("src/cart.test.ts > cart > adds an item");
    }

    @Test
    void pytest() {
        var r = TestReport.parse(List.of(
                        "FAILED tests/test_cart.py::test_total - assert 3 == 4",
                        "========== 1 failed, 7 passed, 1 skipped, 1 xfailed in 0.42s =========="))
                .orElseThrow();
        assertThat(r).isEqualTo(new TestReport(7, 1, 2, "tests", List.of("tests/test_cart.py::test_total")));
    }

    @Test
    void goCountsPackages() {
        var r = TestReport.parse(List.of(
                        "--- FAIL: TestTotal (0.00s)",
                        "FAIL",
                        "FAIL\texample.com/shop/cart\t0.012s",
                        "ok  \texample.com/shop/users\t0.020s",
                        "ok  \texample.com/shop/mail\t(cached)"))
                .orElseThrow();
        assertThat(r).isEqualTo(new TestReport(2, 1, 0, "packages", List.of("TestTotal")));
    }

    @Test
    void cargoAddsUpEveryTestBinary() {
        var r = TestReport.parse(List.of(
                        "test cart::adds ... FAILED",
                        "test result: FAILED. 3 passed; 1 failed; 0 ignored; 0 measured; 0 filtered out",
                        "test result: ok. 2 passed; 0 failed; 1 ignored; 0 measured; 0 filtered out"))
                .orElseThrow();
        assertThat(r).isEqualTo(new TestReport(5, 1, 1, "tests", List.of("cart::adds")));
    }

    @Test
    void gradle() {
        var r = TestReport.parse(List.of(
                        "CartTest > addsAnItem() FAILED",
                        "    org.opentest4j.AssertionFailedError at CartTest.java:12",
                        "12 tests completed, 1 failed, 2 skipped"))
                .orElseThrow();
        assertThat(r).isEqualTo(new TestReport(9, 1, 2, "tests", List.of("CartTest > addsAnItem()")));
    }

    @Test
    void mavenUsesTheTotalLine() {
        var r = TestReport.parse(List.of(
                        "[ERROR] Tests run: 3, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.1 s <<< FAILURE! -- in CartTest",
                        "[ERROR] Failures: ",
                        "[ERROR]   CartTest.addsAnItem:12 expected: <4> but was: <3>",
                        "[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.1 s -- in UserTest",
                        "[ERROR] Tests run: 5, Failures: 1, Errors: 1, Skipped: 1"))
                .orElseThrow();
        assertThat(r).isEqualTo(new TestReport(2, 2, 1, "tests", List.of("CartTest.addsAnItem")));
    }

    @Test
    void unknownOutputHasNoReport() {
        assertThat(TestReport.parse(List.of("all good", "done"))).isEmpty();
    }
}
