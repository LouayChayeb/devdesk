package io.github.louch.devdesk.cli;

import io.github.louch.devdesk.core.diagnosis.Ghosts;
import io.github.louch.devdesk.core.diagnosis.Introspection;
import io.github.louch.devdesk.core.diagnosis.PortOwners;
import io.github.louch.devdesk.core.diagnosis.ProjectLinks;
import io.github.louch.devdesk.core.leftovers.Leftover;
import io.github.louch.devdesk.core.leftovers.LeftoverFinder;
import io.github.louch.devdesk.core.network.Links;
import io.github.louch.devdesk.core.project.ProjectSpec;
import io.github.louch.devdesk.core.project.Workload;
import io.github.louch.devdesk.docker.DockerConnection;
import io.github.louch.devdesk.linux.project.ProjectLock;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code devdesk ps}: everything of this project that runs on the machine right now, and who owns it. A DevDesk, a
 * killed DevDesk (survivors), or nobody (ghosts started by hand). Changes nothing.
 */
@Command(name = "ps", description = "Show what of this project runs on the machine right now, and who owns it.")
final class PsCommand implements Callable<Integer> {

    @Option(
            names = {"-f", "--file"},
            defaultValue = "devdesk.yaml",
            description = "The project file.")
    Path file;

    private final Map<String, String> env;
    private final Introspection system;
    private final LeftoverFinder scopes;
    private final Supplier<DockerConnection.Result> docker;
    private final Clock clock;
    private final PrintStream out;
    private final PrintStream err;

    PsCommand(
            Map<String, String> env,
            Introspection system,
            LeftoverFinder scopes,
            Supplier<DockerConnection.Result> docker,
            Clock clock,
            PrintStream out,
            PrintStream err) {
        this.env = env;
        this.system = system;
        this.scopes = scopes;
        this.docker = docker;
        this.clock = clock;
        this.out = out;
        this.err = err;
    }

    @Override
    public Integer call() {
        Optional<ProjectFile> loaded = ProjectFile.load(file, env, err);
        if (loaded.isEmpty()) {
            return ProjectFile.INVALID;
        }
        ProjectSpec project = loaded.get().project();
        out.println(project.name() + " on this machine  (" + project.root() + ")");

        // Who owns the project right now? Taking the lock for a moment answers it: busy = a living DevDesk has it.
        Set<Long> devdesks = new HashSet<>(Set.of(ProcessHandle.current().pid()));
        Optional<String> owner = switch (ProjectLock.acquire(env, project, "devdesk ps")) {
            case ProjectLock.Result.Busy b -> {
                b.pid().ifPresent(devdesks::add);
                yield Optional.of(b.owner());
            }
            case ProjectLock.Result.Acquired a -> {
                a.lock().close();
                yield Optional.empty();
            }
        };
        List<Leftover> owned = new ArrayList<>(scopes.find(project));
        boolean hasContainers =
                project.services().values().stream().anyMatch(s -> s.workload() instanceof Workload.Container);
        if (hasContainers && docker.get() instanceof DockerConnection.Result.Connected c) {
            owned.addAll(c.runtime().leftovers().find(project));
        }

        out.println();
        if (owner.isPresent()) {
            out.println("Run by a DevDesk  (" + owner.get() + ")");
            owned.forEach(this::print);
            if (owned.isEmpty()) {
                out.println("  (nothing running yet, or it runs without systemd scopes)");
            }
        } else if (!owned.isEmpty()) {
            out.println("Left behind by a DevDesk that was killed  (`devdesk up` stops these first)");
            owned.forEach(this::print);
        } else {
            out.println("No DevDesk runs this project.");
        }

        printConnections(project, owned);

        Set<Long> managed = new HashSet<>();
        owned.forEach(l -> managed.addAll(l.pids()));
        List<Ghosts.Ghost> ghosts = Ghosts.find(project, system, managed, devdesks);
        out.println();
        if (ghosts.isEmpty()) {
            out.println("No other programs of yours are running in this project's folder.");
            return 0;
        }
        out.println("Started by hand, not by DevDesk  (DevDesk never stops these)");
        for (Ghosts.Ghost g : ghosts) {
            String folder = g.process()
                    .workingDirectory()
                    .map(d -> project.root().relativize(d).toString())
                    .orElse("?");
            String age = g.process()
                    .startedAt()
                    .map(t -> ", for " + PortOwners.age(Duration.between(t, clock.instant())))
                    .orElse("");
            out.println("  PID " + g.process().pid() + "  `" + g.process().command() + "`  in "
                    + (folder.isEmpty() ? "." : folder + "/")
                    + age + (g.children() > 0 ? "  (+" + g.children() + " child processes)" : "")
                    + (g.ports().isEmpty() ? "" : "  ports " + g.ports()));
            g.blocks(project)
                    .forEach(
                            (service, port) -> out.println("      ↳ blocks " + service + ", which needs port " + port));
        }
        return 0;
    }

    /** "web → api :3001  2 open". Containers' own connections live in their network namespace, so only the host's side shows. */
    private void printConnections(ProjectSpec project, List<Leftover> owned) {
        Map<String, Set<Long>> pids = new TreeMap<>();
        owned.stream()
                .filter(l -> l.kind() == Leftover.Kind.PROCESS)
                .forEach(l ->
                        pids.computeIfAbsent(l.service(), k -> new HashSet<>()).addAll(l.pids()));
        if (pids.isEmpty()) {
            return;
        }
        List<Links.Link> links = ProjectLinks.of(project, pids, system);
        out.println();
        if (links.isEmpty()) {
            out.println("No open connections from the services right now.");
            return;
        }
        out.println("Who talks to whom  (open TCP connections right now)");
        for (Links.Link l : links) {
            String port = l.target().substring(l.target().lastIndexOf(':') + 1);
            out.println(
                    "  " + l.from() + " → " + l.to().map(to -> to + " :" + port).orElse(l.target() + "  (outside)")
                            + "  " + l.open() + " open");
        }
    }

    private void print(Leftover l) {
        out.println("  " + l.service() + "  " + l.description() + (l.pids().isEmpty() ? "" : "  PIDs " + l.pids())
                + l.since()
                        .map(t -> ", for " + PortOwners.age(Duration.between(t, clock.instant())))
                        .orElse(""));
    }
}
