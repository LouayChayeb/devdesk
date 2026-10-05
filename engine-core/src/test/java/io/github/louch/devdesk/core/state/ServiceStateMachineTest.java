package io.github.louch.devdesk.core.state;

import static io.github.louch.devdesk.core.state.ServiceStateMachine.next;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.louch.devdesk.core.process.ExitStatus;
import io.github.louch.devdesk.core.state.ServiceEvent.CrashLoopDetected;
import io.github.louch.devdesk.core.state.ServiceEvent.DependenciesReady;
import io.github.louch.devdesk.core.state.ServiceEvent.DependencyFailed;
import io.github.louch.devdesk.core.state.ServiceEvent.Exited;
import io.github.louch.devdesk.core.state.ServiceEvent.HealthFailed;
import io.github.louch.devdesk.core.state.ServiceEvent.HealthPassed;
import io.github.louch.devdesk.core.state.ServiceEvent.LaunchFailed;
import io.github.louch.devdesk.core.state.ServiceEvent.Launched;
import io.github.louch.devdesk.core.state.ServiceEvent.RestartDue;
import io.github.louch.devdesk.core.state.ServiceEvent.StartRequested;
import io.github.louch.devdesk.core.state.ServiceEvent.StopFinished;
import io.github.louch.devdesk.core.state.ServiceEvent.StopRequested;
import io.github.louch.devdesk.core.state.ServiceState.Blocked;
import io.github.louch.devdesk.core.state.ServiceState.Completed;
import io.github.louch.devdesk.core.state.ServiceState.CrashLoop;
import io.github.louch.devdesk.core.state.ServiceState.Crashed;
import io.github.louch.devdesk.core.state.ServiceState.FailedToStart;
import io.github.louch.devdesk.core.state.ServiceState.Healthy;
import io.github.louch.devdesk.core.state.ServiceState.Running;
import io.github.louch.devdesk.core.state.ServiceState.Starting;
import io.github.louch.devdesk.core.state.ServiceState.Stopped;
import io.github.louch.devdesk.core.state.ServiceState.Stopping;
import io.github.louch.devdesk.core.state.ServiceState.Unhealthy;
import io.github.louch.devdesk.core.state.ServiceState.WaitingDeps;
import org.junit.jupiter.api.Test;

class ServiceStateMachineTest {

    private static final ExitStatus CRASH = ExitStatus.of(1);

    @Test
    void happyPathFromStoppedToHealthyAndBack() {
        ServiceState s = new Stopped();
        s = next(s, new StartRequested());
        assertThat(s).isEqualTo(new WaitingDeps());
        s = next(s, new DependenciesReady());
        assertThat(s).isEqualTo(new Starting(0));
        s = next(s, new Launched(4121));
        assertThat(s).isEqualTo(new Running(4121, 0));
        s = next(s, new HealthPassed());
        assertThat(s).isEqualTo(new Healthy(4121, 0));
        s = next(s, new StopRequested());
        assertThat(s).isEqualTo(new Stopping());
        s = next(s, new Exited(ExitStatus.of(143))); // its exit during a stop is expected
        assertThat(s).isEqualTo(new Stopping());
        s = next(s, new StopFinished());
        assertThat(s).isEqualTo(new Stopped());
    }

    @Test
    void healthGoesDownAndRecovers() {
        ServiceState s = next(new Healthy(7, 0), new HealthFailed("HTTP 503"));
        assertThat(s).isEqualTo(new Unhealthy(7, 0, "HTTP 503"));
        assertThat(next(s, new HealthPassed())).isEqualTo(new Healthy(7, 0));
        assertThat(next(new Running(7, 0), new HealthFailed("refused"))).isEqualTo(new Unhealthy(7, 0, "refused"));
    }

    @Test
    void exitCodeZeroCompletesAnythingElseCrashes() {
        assertThat(next(new Running(7, 2), new Exited(ExitStatus.of(0)))).isEqualTo(new Completed());
        assertThat(next(new Healthy(7, 2), new Exited(CRASH))).isEqualTo(new Crashed(CRASH, 2));
        assertThat(next(new Unhealthy(7, 2, "x"), new Exited(ExitStatus.of(137))))
                .isEqualTo(new Crashed(ExitStatus.of(137), 2));
    }

    @Test
    void restartsAreCountedAndCrashLoopEndsThem() {
        ServiceState s = next(new Crashed(CRASH, 2), new RestartDue());
        assertThat(s).isEqualTo(new Starting(3));
        assertThat(next(s, new Launched(9))).isEqualTo(new Running(9, 3));
        assertThat(next(new Crashed(CRASH, 5), new CrashLoopDetected())).isEqualTo(new CrashLoop(CRASH, 5));
    }

    @Test
    void failedDependencyBlocksAndLaunchFailureIsKept() {
        assertThat(next(new WaitingDeps(), new DependencyFailed("db", "db crashed (code 1)")))
                .isEqualTo(new Blocked("db crashed (code 1)", "db"));
        assertThat(next(new Starting(0), new LaunchFailed("no such directory")))
                .isEqualTo(new FailedToStart("no such directory"));
    }

    @Test
    void everyEndStateCanBeStartedAgain() {
        for (ServiceState end : new ServiceState[] {
            new Stopped(),
            new Completed(),
            new Crashed(CRASH, 1),
            new FailedToStart("x"),
            new CrashLoop(CRASH, 5),
            new Blocked("x", "db")
        }) {
            assertThat(next(end, new StartRequested())).as("start from %s", end).isEqualTo(new WaitingDeps());
        }
    }

    @Test
    void stopIsSafeFromAnyState() {
        assertThat(next(new Running(1, 0), new StopRequested())).isEqualTo(new Stopping());
        assertThat(next(new WaitingDeps(), new StopRequested())).isEqualTo(new Stopped());
        assertThat(next(new Crashed(CRASH, 0), new StopRequested())).isEqualTo(new Stopped());
        assertThat(next(new Stopped(), new StopRequested())).isEqualTo(new Stopped());
    }

    @Test
    void lateEventsAreIgnoredNotFatal() {
        // A probe or back-off timer that fires just after a stop must not break anything.
        assertThat(next(new Stopping(), new HealthPassed())).isEqualTo(new Stopping());
        assertThat(next(new Stopped(), new HealthFailed("x"))).isEqualTo(new Stopped());
        assertThat(next(new Stopped(), new RestartDue())).isEqualTo(new Stopped());
        assertThat(next(new Healthy(1, 0), new StartRequested())).isEqualTo(new Healthy(1, 0));
    }

    @Test
    void impossibleEventsAreBugsAndFailLoudly() {
        assertThatThrownBy(() -> next(new Stopped(), new Launched(1)))
                .isInstanceOf(IllegalTransitionException.class)
                .hasMessageContaining("Bug");
        assertThatThrownBy(() -> next(new Running(1, 0), new DependenciesReady()))
                .isInstanceOf(IllegalTransitionException.class);
        assertThatThrownBy(() -> next(new Stopped(), new Exited(CRASH))).isInstanceOf(IllegalTransitionException.class);
        assertThatThrownBy(() -> next(new Running(1, 0), new StopFinished()))
                .isInstanceOf(IllegalTransitionException.class);
    }
}
