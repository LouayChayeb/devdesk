package io.github.louch.devdesk.daemon;

import io.github.louch.devdesk.core.project.ProjectSpec;
import io.github.louch.devdesk.core.project.ServiceSpec;
import io.github.louch.devdesk.core.project.Workload;
import io.github.louch.devdesk.core.state.ServiceState;
import io.github.louch.devdesk.core.supervisor.ServiceEnvironment;
import io.github.louch.devdesk.core.terminal.ShellCommand;
import io.github.louch.devdesk.core.terminal.Terminals;
import io.github.louch.devdesk.docker.DockerConnection;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * "Shell" on a service: a terminal in the service's folder with the environment it gets, or inside its container.
 * Decides what to run and refuses, with the reason, when it shouldn't run.
 */
final class ServiceShells {

    /** A started shell, or why not (said in the terminal window). */
    sealed interface Result {
        record Opened(Terminals.Terminal terminal, String title) implements Result {}

        record Refused(String reason) implements Result {}
    }

    private final EngineSession session;
    private final Terminals terminals;
    private final Map<String, String> environment;

    ServiceShells(EngineSession session, Terminals terminals, Map<String, String> environment) {
        this.session = session;
        this.terminals = terminals;
        this.environment = environment;
    }

    Result open(String service, int columns, int rows) {
        Optional<EngineSession.Current> current = session.current();
        if (current.isEmpty() || !current.get().project().services().containsKey(service)) {
            return new Result.Refused("there's no service called " + service + " in the open project");
        }
        // A shell runs whatever you type, with the project's settings and secrets: same rule as running the project.
        if (!session.trusted()) {
            return new Result.Refused("this project isn't trusted yet. Review its commands and trust it first");
        }
        ProjectSpec project = current.get().project();
        ServiceSpec spec = project.services().get(service);
        try {
            if (spec.workload() instanceof Workload.Container) {
                return container(project, spec, current.get().states().get(service), columns, rows);
            }
            List<String> command =
                    ShellCommand.forService(project.name(), spec, environment.getOrDefault("SHELL", ""), "docker");
            // The exact values the service starts with (env files, its own block, your shell), in its own folder.
            Map<String, String> values = ServiceEnvironment.values(project, spec, environment);
            return new Result.Opened(
                    terminals.open(command, spec.directory(), values, columns, rows),
                    service + " · " + spec.directory());
        } catch (IOException e) {
            return new Result.Refused(e.getMessage());
        }
    }

    private Result container(ProjectSpec project, ServiceSpec spec, ServiceState state, int columns, int rows)
            throws IOException {
        boolean up = state instanceof ServiceState.Running
                || state instanceof ServiceState.Healthy
                || state instanceof ServiceState.Unhealthy;
        if (!up) {
            return new Result.Refused(spec.name() + " isn't running. Its shell opens inside the running container: "
                    + "start " + spec.name() + " first");
        }
        // The service runs, so Docker answers; only its socket's address is needed, for the docker command.
        String socket = DockerConnection.socketPath(environment);
        Optional<String> cli = containerCli(socket);
        if (cli.isEmpty()) {
            return new Result.Refused("opening a shell in a container needs the docker or podman command, and neither "
                    + "is installed (DevDesk itself talks to the socket directly). Install one of them");
        }
        Map<String, String> values = new HashMap<>(environment);
        if (cli.get().equals("docker")) {
            values.put("DOCKER_HOST", socket); // the same Docker (or Podman) the engine uses
        }
        List<String> command = ShellCommand.forService(project.name(), spec, "", cli.get());
        return new Result.Opened(
                terminals.open(command, project.root(), values, columns, rows),
                spec.name() + " · inside container " + command.get(3));
    }

    /** podman for Podman's socket when it's installed, else docker; docker also speaks to Podman's socket. */
    private Optional<String> containerCli(String socket) {
        List<String> order = socket.contains("podman") ? List.of("podman", "docker") : List.of("docker", "podman");
        return order.stream().filter(this::onPath).findFirst();
    }

    private boolean onPath(String program) {
        for (String dir : environment.getOrDefault("PATH", "/usr/bin:/bin").split(":")) {
            if (!dir.isBlank() && Files.isExecutable(Path.of(dir, program))) {
                return true;
            }
        }
        return false;
    }
}
