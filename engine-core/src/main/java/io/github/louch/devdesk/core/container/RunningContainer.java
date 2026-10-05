package io.github.louch.devdesk.core.container;

/**
 * A container DevDesk created and still owns.
 *
 * @param id Docker's container id
 * @param name readable name, {@code devdesk-<project>-<service>}
 * @param pid the host PID of the container's main process (useful for diagnosis), 0 if unknown
 */
public record RunningContainer(String service, String id, String name, long pid) {}
