package io.github.louch.devdesk.core.terminal;

import io.github.louch.devdesk.core.container.ContainerRuntime;
import io.github.louch.devdesk.core.project.ServiceSpec;
import io.github.louch.devdesk.core.project.Workload;
import java.util.List;

/**
 * The command behind "open a shell in this service". A container gets a shell inside it ({@code docker exec}); a
 * command service gets the user's own shell in its folder (the environment is added by the caller).
 */
public final class ShellCommand {

    /** bash when the image has it (most do), else plain sh (alpine, distroless-ish images). */
    static final String BASH_OR_SH = "if command -v bash >/dev/null 2>&1; then exec bash; else exec sh; fi";

    private ShellCommand() {}

    /**
     * @param project the project's name, part of the container's name
     * @param userShell the user's {@code $SHELL}, for a command service
     * @param containerCli {@code docker} or {@code podman}: whichever this machine has
     */
    public static List<String> forService(String project, ServiceSpec service, String userShell, String containerCli) {
        if (service.workload() instanceof Workload.Container) {
            // -i keeps stdin open, -t gives the shell a terminal inside the container: like typing it by hand.
            return List.of(
                    containerCli,
                    "exec",
                    "-it",
                    ContainerRuntime.containerName(project, service.name()),
                    "sh",
                    "-c",
                    BASH_OR_SH);
        }
        return List.of(userShell.isBlank() ? "/bin/bash" : userShell);
    }
}
