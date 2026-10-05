package io.github.louch.devdesk.core.supervisor;

import io.github.louch.devdesk.core.diagnosis.Finding;
import io.github.louch.devdesk.core.process.OutputLine;
import io.github.louch.devdesk.core.resources.ResourceSample;
import io.github.louch.devdesk.core.state.ServiceState;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Everything a project reports while it runs. The CLI (and later the UI) only display these. */
public sealed interface ProjectEvent {

    /**
     * A service moved to a new state.
     *
     * @param sequence increases by one per state change, so clients can tell if they missed something
     * @param reason plain words: why it changed ("db is healthy", "command not found (code 127)...")
     */
    record StateChanged(long sequence, String service, ServiceState from, ServiceState to, String reason, Instant at)
            implements ProjectEvent {}

    /** A line of a service's output. */
    record Output(String service, OutputLine line) implements ProjectEvent {}

    /** CPU and memory of every running service, measured just now. */
    record Resources(Map<String, ResourceSample> services) implements ProjectEvent {

        public Resources {
            services = Map.copyOf(services);
        }
    }

    /** Why a service failed: findings with evidence and a fix (see the diagnosis package). */
    record Diagnosis(String service, List<Finding> findings) implements ProjectEvent {

        public Diagnosis {
            findings = List.copyOf(findings);
        }
    }

    /** Something the user should know that isn't a state change, e.g. processes left behind after a stop. */
    record Warning(String service, String message) implements ProjectEvent {}
}
