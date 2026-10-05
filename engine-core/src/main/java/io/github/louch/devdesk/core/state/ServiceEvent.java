package io.github.louch.devdesk.core.state;

import io.github.louch.devdesk.core.process.ExitStatus;

/** Something that happened to a service. The state machine turns (state, event) into the next state. */
public sealed interface ServiceEvent {

    /** The user asked to start it. */
    record StartRequested() implements ServiceEvent {}

    /** Every dependency reached its required condition. */
    record DependenciesReady() implements ServiceEvent {}

    /** A dependency failed, so this service can't start. */
    record DependencyFailed(String cause, String reason) implements ServiceEvent {}

    /** The OS started the process. */
    record Launched(long pid) implements ServiceEvent {}

    /** The OS refused to start it. */
    record LaunchFailed(String reason) implements ServiceEvent {}

    /** The health check passed. */
    record HealthPassed() implements ServiceEvent {}

    /** The health check failed often enough to count (after start_period and retries). */
    record HealthFailed(String reason) implements ServiceEvent {}

    /** The process ended by itself. */
    record Exited(ExitStatus exit) implements ServiceEvent {}

    /** The restart policy decided to start it again (after its back-off delay). */
    record RestartDue() implements ServiceEvent {}

    /** It crashed too many times in a short window. */
    record CrashLoopDetected() implements ServiceEvent {}

    /** The user asked to stop it. */
    record StopRequested() implements ServiceEvent {}

    /** The stop sequence finished: nothing of it is left running. */
    record StopFinished() implements ServiceEvent {}
}
