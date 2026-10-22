package io.github.louch.devdesk.core.stress;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StressTest {

    @Test
    void onlyThisMachineIsEverLoaded() {
        assertThat(new LoadPlan("GET", "http://localhost:3000/x", Map.of(), "", 50, 60).problem())
                .isEmpty();
        assertThat(new LoadPlan("GET", "http://127.0.0.1:3000/x", Map.of(), "", 50, 60).problem())
                .isEmpty();
        assertThat(new LoadPlan("GET", "https://example.com/", Map.of(), "", 50, 60).problem())
                .hasValueSatisfying(p -> assertThat(p).contains("only sends load to this machine"));
        assertThat(new LoadPlan("GET", "http://localhost/", Map.of(), "", 5000, 60).problem())
                .isPresent();
        assertThat(new LoadPlan("GET", "http://localhost/", Map.of(), "", 5, 1).problem())
                .isPresent();
    }

    @Test
    void callersClimbToTheMaximum() {
        var p = new LoadPlan("GET", "http://localhost/", Map.of(), "", 100, 10);
        assertThat(p.concurrencyAt(0)).isEqualTo(10);
        assertThat(p.concurrencyAt(4)).isEqualTo(50);
        assertThat(p.concurrencyAt(9)).isEqualTo(100);
    }

    private static List<LoadSecond> run(int breakAt) {
        List<LoadSecond> s = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            boolean broken = i >= breakAt;
            s.add(new LoadSecond(
                    i,
                    (i + 1) * 10,
                    100,
                    broken ? 30 : 0,
                    10,
                    broken ? 900 : 15,
                    20,
                    broken ? Map.of("502", 30) : Map.of()));
        }
        return s;
    }

    @Test
    void theServiceThatFellOverFirstIsNamed() {
        List<StressVerdict.ServicePoint> points = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            points.add(new StressVerdict.ServicePoint(i, "gateway", "healthy", 30, 1));
            points.add(new StressVerdict.ServicePoint(i, "api", i >= 7 ? "unhealthy" : "healthy", 99, 1));
        }
        var v = StressVerdict.of(run(6), points);
        assertThat(v.breakingSecond()).contains(6);
        assertThat(v.breakingConcurrency()).contains(70);
        assertThat(v.firstToGiveUp()).contains("api");
        assertThat(v.text()).contains("It broke at second 7", "api gave up first");
    }

    @Test
    void withoutACrashTheBusiestServiceIsTheBottleneck() {
        List<StressVerdict.ServicePoint> points = List.of(
                new StressVerdict.ServicePoint(6, "db", "healthy", 99, 1),
                new StressVerdict.ServicePoint(6, "api", "healthy", 40, 1));
        var v = StressVerdict.of(run(6), points);
        assertThat(v.firstToGiveUp()).contains("db");
        assertThat(v.text()).contains("db was at full CPU");
        var held = StressVerdict.of(run(99), List.of());
        assertThat(held.breakingSecond()).isEmpty();
        assertThat(held.text()).startsWith("It held");
    }
}
