package io.github.louch.devdesk.core.diagnosis;

import io.github.louch.devdesk.core.network.Listener;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** A port the service needs is taken by a program that isn't part of this service. */
public final class PortConflictRule implements DiagnosisRule {

    @Override
    public String id() {
        return "port-conflict";
    }

    @Override
    public List<Finding> evaluate(DiagnosisContext ctx, String service) {
        if (ctx.stateOf(service).isActive()) {
            return List.of(); // while it runs, the listener on its port is (usually) the service itself
        }
        List<Finding> findings = new ArrayList<>();
        for (int port : ctx.project().services().get(service).ports()) {
            Optional<Listener> taken = ctx.system().listenerOn(port);
            if (taken.isPresent()
                    && taken.get()
                            .pid()
                            .filter(ctx.historyOf(service).pids()::contains)
                            .isEmpty()) {
                Listener l = taken.get();
                PortOwners.Explanation who = PortOwners.explain(l, ctx.system(), ctx.project(), ctx.now());
                findings.add(new Finding(
                        id(),
                        Finding.Severity.ERROR,
                        service,
                        "Port " + port + " is already in use",
                        service + " needs port " + port + ", but it's taken by " + who.who() + ".",
                        List.of("/proc/net/tcp: " + l.address() + ":" + port + " LISTEN, uid " + l.uid()
                                + l.pid().map(p -> " → PID " + p).orElse(" (owner not visible without root)")),
                        List.of(who.fix(), "or change the port " + service + " uses in devdesk.yaml")));
            }
        }
        return findings;
    }
}
