package io.github.louch.devdesk.core.http;

import java.util.List;

/** Where a project's mock routes are kept (a JSON file per project on Linux; memory in tests). */
public interface MockBook {

    List<MockRoutes.Route> load();

    void store(List<MockRoutes.Route> routes);
}
