package io.github.louch.devdesk.core.removal;

import io.github.louch.devdesk.core.project.ProjectSpec;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * "Remove": deletes everything DevDesk made for a project, then looks again, from scratch, for anything left. The
 * project must be stopped first. What the user made (a folder they opened, their own volumes and images) is never in
 * the list, because adapters only report what carries the project's key.
 */
public final class ProjectRemoval {

    /**
     * What would be deleted, shown before anything is.
     *
     * @param kept what is deliberately left, with the reason (a clone another project lives in)
     */
    public record Plan(List<Removable> items, List<String> kept) {
        public Plan {
            items = List.copyOf(items);
            kept = List.copyOf(kept);
        }

        /** A plan that keeps nothing on purpose. */
        public Plan(List<Removable> items) {
            this(items, List.of());
        }

        /** Whether data goes too (database volumes, the cloned folder): the confirmation must say so. */
        public boolean losesData() {
            return items.stream().anyMatch(Removable::data);
        }
    }

    /**
     * @param failed what couldn't be deleted, and why
     * @param leftovers what a fresh look still finds afterwards: empty when the removal is complete
     */
    public record Report(List<Removable> removed, List<String> failed, List<Removable> leftovers) {
        public Report {
            removed = List.copyOf(removed);
            failed = List.copyOf(failed);
            leftovers = List.copyOf(leftovers);
        }

        public boolean clean() {
            return failed.isEmpty() && leftovers.isEmpty();
        }
    }

    private final List<RemovalPort> ports;

    public ProjectRemoval(List<RemovalPort> ports) {
        this.ports = List.copyOf(ports);
    }

    public Plan plan(ProjectSpec project, boolean cloned) {
        List<Removable> all = new ArrayList<>();
        ports.forEach(p -> all.addAll(safeFind(p, project, cloned)));
        all.sort(Comparator.comparing(Removable::kind));
        List<String> kept = new ArrayList<>();
        ports.forEach(p -> {
            try {
                kept.addAll(p.keeps(project, cloned));
            } catch (RuntimeException e) {
                // nothing to report from a port that can't answer now
            }
        });
        return new Plan(all, kept);
    }

    /**
     * Deletes the plan's items in order (processes before their containers, containers before their networks…). Each
     * goes to the port that finds it now: what is no longer there is skipped, and nothing outside a fresh find is
     * ever deleted, whatever the plan says.
     */
    public Report remove(ProjectSpec project, boolean cloned, Plan plan) {
        Map<Removable, RemovalPort> owners = new HashMap<>();
        ports.forEach(p -> safeFind(p, project, cloned).forEach(r -> owners.putIfAbsent(r, p)));
        List<Removable> removed = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        plan.items().stream().sorted(Comparator.comparing(Removable::kind)).forEach(r -> {
            RemovalPort owner = owners.get(r);
            if (owner == null) {
                removed.add(r); // gone already (a container removed with its project, say)
                return;
            }
            Optional<String> why;
            try {
                why = owner.delete(r);
            } catch (RuntimeException e) {
                // One thing that can't be deleted must not stop the others, nor end in a stack trace.
                why = Optional.of(String.valueOf(e.getMessage()));
            }
            if (why.isPresent()) {
                failed.add(r.what() + ": " + why.get());
            } else {
                removed.add(r);
            }
        });
        return new Report(removed, failed, plan(project, cloned).items());
    }

    private static List<Removable> safeFind(RemovalPort port, ProjectSpec project, boolean cloned) {
        try {
            return port.find(project, cloned);
        } catch (RuntimeException e) {
            return List.of(); // what it can't list now isn't deleted; the report's fresh look tries again
        }
    }
}
