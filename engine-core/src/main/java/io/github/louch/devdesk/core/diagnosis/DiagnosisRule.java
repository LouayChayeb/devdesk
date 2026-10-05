package io.github.louch.devdesk.core.diagnosis;

import java.util.List;

/** Recognises one kind of problem. Rules only read the context: they never change anything. */
public interface DiagnosisRule {

    /** Short stable id, e.g. "port-conflict". */
    String id();

    /**
     * True for rules about slow problems in running services (like memory growth). These are checked every half
     * minute while a project runs, not only when something fails.
     */
    default boolean continuous() {
        return false;
    }

    /** Findings about {@code service} (empty if this rule sees no problem there). Must not throw. */
    List<Finding> evaluate(DiagnosisContext ctx, String service);
}
