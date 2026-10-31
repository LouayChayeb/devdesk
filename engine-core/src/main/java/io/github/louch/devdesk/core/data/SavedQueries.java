package io.github.louch.devdesk.core.data;

import java.util.List;

/** Where a project's saved SQL queries are kept (a JSON file per project on Linux; memory in tests). */
public interface SavedQueries {

    /** Every saved query of the project; empty when none were saved. */
    List<SavedQuery> load();

    /** Replaces the saved queries with {@code queries}. */
    void store(List<SavedQuery> queries);

    /** A query the user named and kept, for one database of the project. */
    record SavedQuery(String id, String service, String name, String sql) {}
}
