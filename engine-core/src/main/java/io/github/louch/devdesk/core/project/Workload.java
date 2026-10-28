package io.github.louch.devdesk.core.project;

import java.nio.file.Path;
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
     * @param image the image to run; with {@code build}, the name DevDesk gives the image it builds
     * @param command overrides the image's default command, e.g. {@code postgres -c log_statement=all}
     * @param volumes {@code name:/path} (named volume) or {@code /host/path:/path} (bind mount, already absolute)
     * @param build how to build the image when it isn't there yet, from the project's own Dockerfile
     * @param entrypoint replaces the image's ENTRYPOINT (compose's {@code entrypoint:}), e.g. a script that prepares
     *     the app before {@code command} runs
     */
    record Container(
            String image,
            List<PortMapping> ports,
            List<String> volumes,
            Optional<String> command,
            Optional<Build> build,
            Optional<String> entrypoint)
            implements Workload {

        public Container {
            ports = List.copyOf(ports);
            volumes = List.copyOf(volumes);
        }

        /** An image to download or already present: nothing to build. */
        public Container(String image, List<PortMapping> ports, List<String> volumes, Optional<String> command) {
            this(image, ports, volumes, command, Optional.empty(), Optional.empty());
        }

        /** The image's own entrypoint. */
        public Container(
                String image,
                List<PortMapping> ports,
                List<String> volumes,
                Optional<String> command,
                Optional<Build> build) {
            this(image, ports, volumes, command, build, Optional.empty());
        }

        @Override
        public String describe() {
            return "container " + image
                    + build.map(b -> " (built from " + b.context() + ")").orElse("")
                    + entrypoint.map(e -> " entrypoint " + e).orElse("")
                    + command.map(c -> " " + c).orElse("")
                    + (ports.isEmpty()
                            ? ""
                            : " ports "
                                    + ports.stream().map(PortMapping::toString).toList())
                    + (volumes.isEmpty() ? "" : " volumes " + volumes);
        }
    }

    /**
     * What {@code docker build} gets, as in compose's {@code build:}.
     *
     * @param context the folder sent to Docker, already absolute
     * @param dockerfile the Dockerfile, relative to {@code context}
     */
    record Build(Path context, String dockerfile) {}

    /**
     * {@code hostPort:containerPort}, published on {@code hostAddress}.
     *
     * @param hostAddress 127.0.0.1 unless the user wrote another address: a dev database shouldn't be reachable from
     *     the whole network by accident
     * @param udp a UDP port ("6831:6831/udp", jaeger's agent), not TCP
     */
    record PortMapping(String hostAddress, int hostPort, int containerPort, boolean udp) {

        /** A TCP port: what nearly every service publishes. */
        public PortMapping(String hostAddress, int hostPort, int containerPort) {
            this(hostAddress, hostPort, containerPort, false);
        }

        @Override
        public String toString() {
            return (hostAddress.equals("127.0.0.1") ? "" : hostAddress + ":") + hostPort + ":" + containerPort
                    + (udp ? "/udp" : "");
        }
    }
}
