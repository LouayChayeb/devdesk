package io.github.louch.devdesk.core.project;

import java.util.List;
import java.util.Optional;

/** What a service actually runs: a shell command on this machine, or a container. */
public sealed interface Workload {

    /** One line for people: the command, or "container postgres:18.1". Shown before trusting a project. */
    String describe();

    /** A shell command line, run through {@code /bin/sh -c}. */
    record Command(String commandLine) implements Workload {
        @Override
        public String describe() {
            return commandLine;
        }
    }

    /**
     * A Docker (or Podman) container.
     *
     * @param command overrides the image's default command, e.g. {@code postgres -c log_statement=all}
     * @param volumes {@code name:/path} (named volume) or {@code /host/path:/path} (bind mount, already absolute)
     */
    record Container(String image, List<PortMapping> ports, List<String> volumes, Optional<String> command)
            implements Workload {

        public Container {
            ports = List.copyOf(ports);
            volumes = List.copyOf(volumes);
        }

        @Override
        public String describe() {
            return "container " + image + command.map(c -> " " + c).orElse("")
                    + (ports.isEmpty()
                            ? ""
                            : " ports "
                                    + ports.stream().map(PortMapping::toString).toList())
                    + (volumes.isEmpty() ? "" : " volumes " + volumes);
        }
    }

    /**
     * {@code hostPort:containerPort}, published on {@code hostAddress}.
     *
     * @param hostAddress 127.0.0.1 unless the user wrote another address: a dev database shouldn't be reachable from
     *     the whole network by accident
     */
    record PortMapping(String hostAddress, int hostPort, int containerPort) {
        @Override
        public String toString() {
            return (hostAddress.equals("127.0.0.1") ? "" : hostAddress + ":") + hostPort + ":" + containerPort;
        }
    }
}
