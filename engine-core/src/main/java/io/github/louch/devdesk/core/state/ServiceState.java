package io.github.louch.devdesk.core.state;

import io.github.louch.devdesk.core.process.ExitStatus;

/** Where a service is in its life. Each state carries the facts that explain it. */
public sealed interface ServiceState {

    /** Short lowercase label for terminals and logs, e.g. "healthy". */
    String label();

    /** True while DevDesk owns a running (or starting) process for it. */
    default boolean isActive() {
        return switch (this) {
            case Starting s -> true;
            case Running r -> true;
            case Healthy h -> true;
            case Unhealthy u -> true;
            case Stopping s -> true;
            default -> false;
        };
    }

    /** Not running, and nothing pending. */
    record Stopped() implements ServiceState {
        public String label() {
            return "stopped";
        }
    }

    /** Asked to start; waiting for its dependencies. */
    record WaitingDeps() implements ServiceState {
        public String label() {
            return "waiting";
        }
    }

    /** Will not start because a dependency failed. {@code cause} is the service at fault. */
    record Blocked(String reason, String cause) implements ServiceState {
        public String label() {
            return "blocked";
        }
    }

    /** Process being launched. */
    record Starting(int restarts) implements ServiceState {
        public String label() {
            return "starting";
        }
    }

    /** Process alive; no health check has passed (or none is configured). */
    record Running(long pid, int restarts) implements ServiceState {
        public String label() {
            return "running";
        }
    }

    /** Process alive and its health check passes. */
    record Healthy(long pid, int restarts) implements ServiceState {
        public String label() {
            return "healthy";
        }
    }

    /** Process alive but its health check keeps failing. */
    record Unhealthy(long pid, int restarts, String reason) implements ServiceState {
        public String label() {
            return "unhealthy";
        }
    }

    /** Stop sequence in progress (SIGTERM, grace period, SIGKILL). */
    record Stopping() implements ServiceState {
        public String label() {
            return "stopping";
        }
    }

    /** The process ended by itself with code 0. */
    record Completed() implements ServiceState {
        public String label() {
            return "completed";
        }
    }

    /** The process ended by itself with an error code or a signal. */
    record Crashed(ExitStatus exit, int restarts) implements ServiceState {
        public String label() {
            return "crashed";
        }
    }

    /** The OS refused to launch it (missing directory, permission...). */
    record FailedToStart(String reason) implements ServiceState {
        public String label() {
            return "failed";
        }
    }

    /** It crashed too often in a short time; DevDesk stopped restarting it. */
    record CrashLoop(ExitStatus lastExit, int restarts) implements ServiceState {
        public String label() {
            return "crash-loop";
        }
    }
}
