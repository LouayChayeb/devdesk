package io.github.louch.devdesk.core.diagnosis;

import java.util.List;

/**
 * One explained problem.
 *
 * @param title short, e.g. "Port 3001 is already in use"
 * @param explanation plain words: what's going on and why it probably happened
 * @param evidence the facts behind it, each with its source, so the user can check it ("/proc/net/tcp: ...")
 * @param actions commands or steps to fix it. Shown to the user, never run automatically
 */
public record Finding(
        String ruleId,
        Severity severity,
        String service,
        String title,
        String explanation,
        List<String> evidence,
        List<String> actions) {

    public Finding {
        evidence = List.copyOf(evidence);
        actions = List.copyOf(actions);
    }

    /** How bad a finding is. */
    public enum Severity {
        /** It stops the project from working. */
        ERROR,
        /** It works, but probably not as intended. */
        WARNING
    }
}
