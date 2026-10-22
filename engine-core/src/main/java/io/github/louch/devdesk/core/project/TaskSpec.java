package io.github.louch.devdesk.core.project;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A one-off command of the project ({@code tasks: seed: ...}): seed, test, lint, reset-db. It runs until it ends, with
 * the same settings services get, after the services it needs are ready.
 *
 * @param directory where it runs (absolute, like a service's)
 * @param environment its own variables, on top of the project's env files
 * @param needs services that must be ready first; DevDesk starts them if they aren't
 * @param confirm a warning the app shows before running ("This deletes every Redis key."), if any
 */
public record TaskSpec(
        String name,
        String command,
        Path directory,
        Map<String, String> environment,
        List<String> needs,
        String description,
        Optional<String> confirm) {

    public TaskSpec {
        environment = Map.copyOf(environment);
        needs = List.copyOf(needs);
    }
}
