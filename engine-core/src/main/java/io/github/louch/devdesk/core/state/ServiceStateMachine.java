package io.github.louch.devdesk.core.state;

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

/**
 * Every allowed state change, in one place. Anything not listed here throws: a surprising event is a bug, and it's
 * better to fail loudly than to drift into a state nobody designed.
 */
public final class ServiceStateMachine {

    private ServiceStateMachine() {}

    /** The state after {@code event}. Pure: no side effects, so every edge is easy to test. */
    public static ServiceState next(ServiceState from, ServiceEvent event) {
        return switch (event) {
            case StartRequested e ->
                switch (from) {
                    case Stopped s -> new WaitingDeps();
                    case Completed s -> new WaitingDeps();
                    case Crashed s -> new WaitingDeps();
                    case FailedToStart s -> new WaitingDeps();
                    case CrashLoop s -> new WaitingDeps();
                    case Blocked s -> new WaitingDeps();
                    default -> from; // already starting or running: starting again is a no-op
                };
            case DependenciesReady e -> require(from, event, from instanceof WaitingDeps, new Starting(0));
            // Also from Blocked: when the dependency it was blocked by recovers but another one fails, the reason
            // must name the one failing now, or the explanation points at a healthy service.
            case DependencyFailed e ->
                require(
                        from,
                        event,
                        from instanceof WaitingDeps || from instanceof Blocked,
                        new Blocked(e.reason(), e.cause()));
            case Launched e ->
                switch (from) {
                    case Starting s -> new Running(e.pid(), s.restarts());
                    default -> throw new IllegalTransitionException(from, event);
                };
            case LaunchFailed e -> require(from, event, from instanceof Starting, new FailedToStart(e.reason()));
            case HealthPassed e ->
                switch (from) {
                    case Running r -> new Healthy(r.pid(), r.restarts());
                    case Unhealthy u -> new Healthy(u.pid(), u.restarts());
                    case Healthy h -> h;
                    default -> from; // a late probe result after a stop or crash: ignore it
                };
            case HealthFailed e ->
                switch (from) {
                    case Running r -> new Unhealthy(r.pid(), r.restarts(), e.reason());
                    case Healthy h -> new Unhealthy(h.pid(), h.restarts(), e.reason());
                    case Unhealthy u -> new Unhealthy(u.pid(), u.restarts(), e.reason());
                    default -> from; // late probe result
                };
            case Exited e ->
                switch (from) {
                    case Running r -> ended(e, r.restarts());
                    case Healthy h -> ended(e, h.restarts());
                    case Unhealthy u -> ended(e, u.restarts());
                    case Stopping s -> s; // we are stopping it: its exit is expected, StopFinished follows
                    default -> throw new IllegalTransitionException(from, event);
                };
            case RestartDue e ->
                switch (from) {
                    case Crashed c -> new Starting(c.restarts() + 1);
                    case Completed c -> new Starting(1);
                    default -> from; // stopped (or restarted by hand) during the back-off wait
                };
            case CrashLoopDetected e ->
                switch (from) {
                    case Crashed c -> new CrashLoop(c.exit(), c.restarts());
                    default -> throw new IllegalTransitionException(from, event);
                };
            case StopRequested e -> from.isActive() ? new Stopping() : new Stopped();
            case StopFinished e -> require(from, event, from instanceof Stopping, new Stopped());
        };
    }

    private static ServiceState ended(Exited e, int restarts) {
        return e.exit().code() == 0 ? new Completed() : new Crashed(e.exit(), restarts);
    }

    private static ServiceState require(ServiceState from, ServiceEvent event, boolean allowed, ServiceState to) {
        if (!allowed) {
            throw new IllegalTransitionException(from, event);
        }
        return to;
    }
}
