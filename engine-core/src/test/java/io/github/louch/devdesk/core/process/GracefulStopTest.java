package io.github.louch.devdesk.core.process;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GracefulStopTest {

    private static final Duration GRACE = Duration.ofMillis(50);
    private static final ProcessLaunchSpec SPEC = new ProcessLaunchSpec("api", "npm start", Path.of("/tmp"), Map.of());

    @Test
    void politeProcessStopsOnSigtermAlone() throws Exception {
        var runtime = new FakeProcessRuntime(false, false);
        var result = new GracefulStop(runtime).stop(runtime.start(SPEC, line -> {}), GRACE);

        assertThat(runtime.received).containsExactly(Signal.TERM);
        assertThat(result).isEqualTo(new StopResult.Stopped(ExitStatus.of(143), false));
    }

    @Test
    void processIgnoringSigtermIsKilledAfterGracePeriod() throws Exception {
        var runtime = new FakeProcessRuntime(true, false);
        var result = new GracefulStop(runtime).stop(runtime.start(SPEC, line -> {}), GRACE);

        assertThat(runtime.received).containsExactly(Signal.TERM, Signal.KILL);
        assertThat(result).isEqualTo(new StopResult.Stopped(ExitStatus.of(137), true));
    }

    @Test
    void survivorsAfterSigkillAreReportedNotHidden() throws Exception {
        var runtime = new FakeProcessRuntime(true, true);
        var result = new GracefulStop(runtime).stop(runtime.start(SPEC, line -> {}), GRACE);

        assertThat(result)
                .isInstanceOfSatisfying(
                        StopResult.LeftBehind.class,
                        left -> assertThat(left.pids()).containsExactly(999L));
    }
}
