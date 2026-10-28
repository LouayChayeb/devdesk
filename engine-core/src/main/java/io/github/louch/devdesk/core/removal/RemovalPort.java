package io.github.louch.devdesk.core.removal;

import io.github.louch.devdesk.core.project.ProjectSpec;
import java.util.List;
import java.util.Optional;

/** Finds and deletes what DevDesk made for a project in one place (Docker, this machine). Adapters implement it. */
public interface RemovalPort {

    /**
     * Only what is provably DevDesk's for this project: a label or a record carrying its key, never a name match.
     *
     * @param cloned whether DevDesk cloned the project's folder (then the folder is DevDesk's too)
     */
    List<Removable> find(ProjectSpec project, boolean cloned);

    /**
     * What this port deliberately leaves, and why ("the clone ~/DevDesk/shop: another project, admin, is in it").
     * Shown with the plan, so nothing is silently skipped.
     */
    default List<String> keeps(ProjectSpec project, boolean cloned) {
        return List.of();
    }

    /** Empty when it's gone; otherwise why it isn't, in plain words. Only called with what {@link #find} returned. */
    Optional<String> delete(Removable removable);
}
