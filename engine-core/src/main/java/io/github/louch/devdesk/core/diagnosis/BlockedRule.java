package io.github.louch.devdesk.core.diagnosis;

import io.github.louch.devdesk.core.state.ServiceState;
import java.util.List;

/** The service never started because something it depends on failed. Points at the root cause. */
public final class BlockedRule implements DiagnosisRule {

    @Override
    public String id() {
        return "blocked";
    }

    @Override
    public List<Finding> evaluate(DiagnosisContext ctx, String service) {
        if (!(ctx.stateOf(service) instanceof ServiceState.Blocked)) {
            return List.of();
        }
        List<String> chain = DiagnosisEngine.causeChain(ctx, service);
        String root = chain.getFirst();
        return List.of(new Finding(
                id(),
                Finding.Severity.ERROR,
                service,
                service + " did not start",
                service + " waits for " + String.join(" → ", chain.reversed().subList(1, chain.size())) + ", and "
                        + root + " is the one that failed. Fix " + root + " and the rest will follow.",
                List.of("dependency chain: " + String.join(" ← ", chain)),
                List.of("see the finding for " + root + " above")));
    }
}
