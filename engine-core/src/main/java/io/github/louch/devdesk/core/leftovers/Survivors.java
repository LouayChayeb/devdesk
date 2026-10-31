package io.github.louch.devdesk.core.leftovers;

import io.github.louch.devdesk.core.project.ProjectSpec;
import java.util.ArrayList;
import java.util.List;

/**
 * Cleans up what a dead DevDesk left behind for a project, before it starts again.
 *
 * <p>Only call this while holding the project's lock: then no living DevDesk owns these processes, so they are
 * survivors, and stopping them is safe (they're provably DevDesk's own).
 */
public final class Survivors {

    /** One leftover and what happened when it was stopped. */
    public record Report(Leftover leftover, String result) {}

    private final List<LeftoverFinder> finders;

    public Survivors(List<LeftoverFinder> finders) {
        this.finders = List.copyOf(finders);
    }

    /** What a dead DevDesk left running for this project. Only looks; stops nothing. */
    public List<Leftover> find(ProjectSpec project) {
        List<Leftover> all = new ArrayList<>();
        finders.forEach(f -> all.addAll(f.find(project)));
        return all;
    }

    /** Finds and stops every survivor, returning what was done (empty if nothing was left behind). */
    public List<Report> cleanUp(ProjectSpec project) {
        List<Report> reports = new ArrayList<>();
        for (LeftoverFinder finder : finders) {
            for (Leftover leftover : finder.find(project)) {
                reports.add(new Report(leftover, finder.remove(leftover)));
            }
        }
        return reports;
    }
}
