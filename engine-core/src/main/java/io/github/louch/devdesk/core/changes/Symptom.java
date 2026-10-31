package io.github.louch.devdesk.core.changes;

import io.github.louch.devdesk.core.diagnosis.Finding;

/**
 * Something wrong right now (or in the last run), as the verdict needs it: which service, which diagnosis rule saw it,
 * and the finding's words (missing settings and programs are named there).
 *
 * @param rule the diagnosis rule id: "health", "crash", "missing-env", "program-missing", "port-conflict"...
 */
public record Symptom(String service, String rule, String text) {

    /** The symptom a finding describes: its service, its rule and its words, which the verdict searches for names. */
    public static Symptom of(Finding f) {
        return new Symptom(f.service(), f.ruleId(), f.title() + " " + f.explanation());
    }

    /** The service doesn't start, or doesn't stay up: what a changed image, code or dependency usually causes. */
    boolean startFailure() {
        return rule.equals("crash")
                || rule.equals("health")
                || rule.equals("program-missing")
                || rule.equals("killed-from-outside");
    }
}
