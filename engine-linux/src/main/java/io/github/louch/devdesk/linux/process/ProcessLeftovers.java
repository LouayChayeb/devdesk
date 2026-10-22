package io.github.louch.devdesk.linux.process;

import io.github.louch.devdesk.core.leftovers.Leftover;
import io.github.louch.devdesk.core.leftovers.LeftoverFinder;
import io.github.louch.devdesk.core.project.ProjectSpec;
import java.util.ArrayList;
import java.util.List;

/** Processes a dead DevDesk left behind, in both modes: systemd scopes, and recorded process groups. */
public final class ProcessLeftovers implements LeftoverFinder {

    private final ScopeLeftovers scopes;
    private final GroupLeftovers groups;

    public ProcessLeftovers(ScopeLeftovers scopes, GroupLeftovers groups) {
        this.scopes = scopes;
        this.groups = groups;
    }

    @Override
    public List<Leftover> find(ProjectSpec project) {
        List<Leftover> all = new ArrayList<>(scopes.find(project));
        all.addAll(groups.find(project));
        return all;
    }

    @Override
    public String remove(Leftover leftover) {
        // Scope ids are systemd unit names; group ids are "<project key>/<group>".
        return leftover.id().endsWith(".scope") ? scopes.remove(leftover) : groups.remove(leftover);
    }
}
