package io.github.louch.devdesk.core.leftovers;

import io.github.louch.devdesk.core.project.ProjectSpec;
import java.util.List;

/** Finds and removes leftovers. Linux looks at systemd scopes, Docker at container labels. */
public interface LeftoverFinder {

    /** Only things provably started by DevDesk for this project (its key in the scope name or a container label). */
    List<Leftover> find(ProjectSpec project);

    /** Stops it politely, then for sure. Returns a plain-words result ("stopped", or why it couldn't). */
    String remove(Leftover leftover);
}
