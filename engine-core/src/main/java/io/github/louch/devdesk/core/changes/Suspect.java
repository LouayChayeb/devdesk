package io.github.louch.devdesk.core.changes;

import java.util.List;

/**
 * One change, judged: how sure DevDesk is that it broke the project, and why.
 *
 * @param score higher = better match with what fails now; only used to order suspects
 * @param services the services this change can reach (empty = the whole project)
 * @param reasons plain words: which finding matches this change ("db uses this image and its health check fails")
 * @param caveat what this judgement is based on, always shown, so nobody mistakes a match for a proof
 */
public record Suspect(
        KnownGood.Change change,
        Confidence confidence,
        int score,
        List<String> services,
        List<String> reasons,
        String caveat) {

    public Suspect {
        services = List.copyOf(services);
        reasons = List.copyOf(reasons);
    }

    /** How sure. */
    public enum Confidence {
        /** Replayed: the image it used before passes the same health check, the current one fails. Images only. */
        PROVEN,
        /** The change matches what fails, but nothing was replayed. */
        LIKELY,
        /** Changed, but nothing that fails points at it. */
        UNKNOWN
    }

    /** Only an image change can be replayed safely (a throwaway container); everything else stays a match. */
    public boolean provable() {
        return change.kind() == KnownGood.Kind.IMAGE;
    }

    Suspect with(Confidence c, String newCaveat) {
        return new Suspect(change, c, score, services, reasons, newCaveat);
    }
}
