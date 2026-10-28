package io.github.louch.devdesk.core.diagnosis;

import io.github.louch.devdesk.core.state.ServiceState;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs every rule and orders the findings root cause first: for a blocked service, the failing dependency is
 * explained before the services waiting on it.
 */
public final class DiagnosisEngine {

    private final List<DiagnosisRule> rules;

    public DiagnosisEngine(List<DiagnosisRule> rules) {
        this.rules = List.copyOf(rules);
    }

    /** The v1 rule set (ARCHITECTURE §9). */
    public static DiagnosisEngine standard() {
        return new DiagnosisEngine(List.of(
                new PortConflictRule(),
                new ProgramMissingRule(),
                new KilledFromOutsideRule(),
                new CrashRule(),
                new WrapperCrashRule(),
                new HealthRule(),
                new SecretRule(),
                new MissingEnvRule(),
                new HalfMadeDatabaseRule(),
                new LogErrorsRule(),
                new MemoryGrowthRule(),
                new SocketGrowthRule(),
                new BlockedRule()));
    }

    /** Why is {@code service} broken? Walks down to the root cause first. */
    public List<Finding> diagnose(DiagnosisContext ctx, String service) {
        List<Finding> findings = new ArrayList<>();
        for (String s : causeChain(ctx, service)) {
            findings.addAll(evaluate(ctx, s));
        }
        return findings;
    }

    /**
     * Only this service's own findings, without walking down the dependency chain. Used while a project runs: the
     * root cause was already explained when it failed, so it isn't repeated for every service waiting on it.
     */
    public List<Finding> explain(DiagnosisContext ctx, String service) {
        return evaluate(ctx, service);
    }

    /** The continuous rules only, for a service that is running fine (see {@link DiagnosisRule#continuous()}). */
    public List<Finding> watch(DiagnosisContext ctx, String service) {
        return evaluate(
                ctx, service, rules.stream().filter(DiagnosisRule::continuous).toList());
    }

    /** Checks before starting anything: every service, in file order. */
    public List<Finding> preflight(DiagnosisContext ctx) {
        List<Finding> findings = new ArrayList<>();
        ctx.project().services().keySet().forEach(s -> findings.addAll(evaluate(ctx, s)));
        return findings;
    }

    private List<Finding> evaluate(DiagnosisContext ctx, String service) {
        return evaluate(ctx, service, rules);
    }

    private static List<Finding> evaluate(DiagnosisContext ctx, String service, List<DiagnosisRule> rules) {
        List<Finding> findings = new ArrayList<>();
        for (DiagnosisRule rule : rules) {
            try {
                findings.addAll(rule.evaluate(ctx, service));
            } catch (RuntimeException e) {
                // One broken rule must not hide what the others found.
                findings.add(new Finding(
                        rule.id(),
                        Finding.Severity.WARNING,
                        service,
                        "Rule " + rule.id() + " failed",
                        "This is a DevDesk bug, please report it: " + e,
                        List.of(),
                        List.of()));
            }
        }
        return findings;
    }

    /** [root cause, ..., service]: follows "blocked because of X" links down to the service that really failed. */
    static List<String> causeChain(DiagnosisContext ctx, String service) {
        List<String> chain = new ArrayList<>(List.of(service));
        String current = service;
        while (ctx.stateOf(current) instanceof ServiceState.Blocked b && !chain.contains(b.cause())) {
            current = b.cause();
            chain.add(current);
        }
        return chain.reversed();
    }
}
