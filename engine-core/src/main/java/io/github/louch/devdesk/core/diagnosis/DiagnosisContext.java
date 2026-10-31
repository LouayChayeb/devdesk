package io.github.louch.devdesk.core.diagnosis;

import io.github.louch.devdesk.core.project.ProjectSpec;
import io.github.louch.devdesk.core.state.ServiceState;
import java.time.Instant;
import java.util.Map;

/**
 * A snapshot of a project at one moment, plus a way to ask the machine questions.
 *
 * @param environment the environment services start with (login env + env_files), to check for missing variables
 * @param now the moment of the snapshot (from the injected clock), for ages like "started 2h ago"
 */
public record DiagnosisContext(
        ProjectSpec project,
        Map<String, ServiceState> states,
        Map<String, ServiceHistory> history,
        Map<String, String> environment,
        Introspection system,
        Instant now) {

    /** What happened to {@code service} recently; an empty history when nothing was recorded. */
    public ServiceHistory historyOf(String service) {
        return history.getOrDefault(service, ServiceHistory.EMPTY);
    }

    /** The service's state at the moment of the snapshot; stopped when it has none. */
    public ServiceState stateOf(String service) {
        return states.getOrDefault(service, new ServiceState.Stopped());
    }
}
