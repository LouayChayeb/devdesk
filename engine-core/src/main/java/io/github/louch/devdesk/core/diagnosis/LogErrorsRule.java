package io.github.louch.devdesk.core.diagnosis;

import io.github.louch.devdesk.core.project.HealthCheckSpec;
import io.github.louch.devdesk.core.project.ServiceSpec;
import io.github.louch.devdesk.core.state.ServiceState;
import java.util.ArrayList;
import java.util.List;

/**
 * A service that passes its check (its port answers, or it simply runs) while its log keeps showing errors: Kafka's
 * order-service logged ~400 errors behind a passing check, Saleor and Chatwoot answered on their ports without a
 * database. Checked while the project runs, like the other slow problems.
 */
public final class LogErrorsRule implements DiagnosisRule {

    /** Distinct error lines (not stack frames) in what it printed lately before this is worth saying. */
    private static final int ENOUGH = 3;

    @Override
    public String id() {
        return "log-errors";
    }

    @Override
    public boolean continuous() {
        return true;
    }

    @Override
    public List<Finding> evaluate(DiagnosisContext ctx, String service) {
        ServiceState state = ctx.stateOf(service);
        if (!(state instanceof ServiceState.Healthy) && !(state instanceof ServiceState.Running)) {
            return List.of();
        }
        ServiceSpec spec = ctx.project().services().get(service);
        // An http or command check asks the app itself: if it passes, the app works despite its log.
        if (spec.health() instanceof HealthCheckSpec.Http || spec.health() instanceof HealthCheckSpec.Command) {
            return List.of();
        }
        var lines = ctx.historyOf(service).recentOutput();
        if (ErrorLines.distinctErrors(lines) < ENOUGH) {
            return List.of();
        }
        List<String> evidence = new ArrayList<>();
        ErrorLines.cause(lines).ifPresent(c -> evidence.add("its log: " + c));
        evidence.add(ErrorLines.distinctErrors(lines) + " different error lines in what it printed lately");
        return List.of(new Finding(
                id(),
                Finding.Severity.WARNING,
                service,
                service + " looks up, but its log shows errors",
                service
                        + (spec.health() instanceof HealthCheckSpec.Tcp
                                ? " accepts connections on its port"
                                : " is running")
                        + ", but it keeps printing errors: it may not work. A port that answers doesn't mean the"
                        + " app behind it does.",
                evidence,
                List.of(
                        "read its log for the first error and fix that",
                        "give it a check that asks the app itself, like health: { type: http, url: <its health"
                                + " URL> }, so DevDesk sees this by itself")));
    }
}
