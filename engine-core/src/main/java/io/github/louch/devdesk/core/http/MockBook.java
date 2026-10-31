package io.github.louch.devdesk.core.http;

import java.util.List;

/** Where a project's mock routes are kept (a JSON file per project on Linux; memory in tests). */
public interface MockBook {

    /** Every mock route of the project, in order; empty when none were saved. */
    List<MockRoutes.Route> load();

    /** Replaces the mock routes with {@code routes}. */
    void store(List<MockRoutes.Route> routes);
}
