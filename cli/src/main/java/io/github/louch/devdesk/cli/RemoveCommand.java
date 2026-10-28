package io.github.louch.devdesk.cli;

import io.github.louch.devdesk.core.removal.ProjectRemoval;
import io.github.louch.devdesk.core.removal.RemovalPort;
import io.github.louch.devdesk.docker.DockerConnection;
import io.github.louch.devdesk.linux.get.GitClone;
import io.github.louch.devdesk.linux.process.GroupLeftovers;
import io.github.louch.devdesk.linux.process.ProcessLeftovers;
import io.github.louch.devdesk.linux.process.ScopeLeftovers;
import io.github.louch.devdesk.linux.project.ProjectLock;
import io.github.louch.devdesk.linux.removal.LocalRemoval;
import io.github.louch.devdesk.linux.trust.TrustStore;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code devdesk remove}: deletes everything DevDesk made for a project (containers, network, its volumes and images,
 * its saved state, the folder if DevDesk cloned it), after showing the list. Then it looks again and says what's left.
 */
@Command(name = "remove", description = "Delete everything DevDesk made for a project. Shows the list and asks first.")
final class RemoveCommand implements Callable<Integer> {

    @Parameters(
            index = "0",
            arity = "0..1",
            paramLabel = "FOLDER",
            defaultValue = ".",
            description = "The project folder (default: here).")
    Path folder;

    @Option(names = "--yes", description = "Don't ask (for scripts and tests).")
    boolean yes;

    private final Map<String, String> env;
    private final TrustStore trust;
    private final BufferedReader in;
    private final PrintStream out;
    private final PrintStream err;

    RemoveCommand(Map<String, String> env, TrustStore trust, BufferedReader in, PrintStream out, PrintStream err) {
        this.env = env;
        this.trust = trust;
        this.in = in;
        this.out = out;
        this.err = err;
    }

    @Override
    public Integer call() throws IOException {
        Path root = folder.toAbsolutePath().normalize();
        Optional<ProjectFile> loaded = ProjectFile.load(root.resolve("devdesk.yaml"), env, err);
        if (loaded.isEmpty()) {
            return ProjectFile.INVALID;
        }
        var project = loaded.get().project();
        // Nothing of it may run while it's removed: the lock proves no other DevDesk runs it.
        try (ProjectLock lock = switch (ProjectLock.acquire(env, project, "devdesk remove")) {
            case ProjectLock.Result.Acquired a -> a.lock();
            case ProjectLock.Result.Busy b -> {
                err.println("devdesk: " + project.name() + " is running (" + b.owner() + "). Stop it first, then"
                        + " remove it");
                yield null;
            }
        }) {
            if (lock == null) {
                return UpCommand.ALREADY_RUNNING;
            }
            var clones = new GitClone(env, GitClone.defaultLog(env), Clock.systemUTC());
            boolean cloned = clones.cloned(root);
            List<RemovalPort> ports = new ArrayList<>();
            Optional<LocalRemoval.RootFiles> rootFiles = Optional.empty();
            switch (DockerConnection.connect(env)) {
                case DockerConnection.Result.Connected c -> {
                    ports.add(c.runtime().removal());
                    rootFiles = Optional.of(c.runtime()::deleteInside); // a database's files in a clone belong to root
                }
                case DockerConnection.Result.Unavailable u ->
                    out.println("Note: Docker isn't available (" + u.reason() + "), so its containers, volumes and"
                            + " images can't be listed now.");
            }
            ports.addFirst(new LocalRemoval(
                    new ProcessLeftovers(new ScopeLeftovers(), new GroupLeftovers(GroupLeftovers.defaultFolder(env))),
                    GitClone.defaultLog(env).getParent(),
                    trust,
                    clones,
                    rootFiles));
            var removal = new ProjectRemoval(ports);
            var plan = removal.plan(project, cloned);
            if (plan.items().isEmpty()) {
                out.println("DevDesk made nothing for " + project.name() + " that is still here.");
                plan.kept().forEach(k -> out.println("It keeps " + k + "."));
                return 0;
            }
            out.println("Remove " + project.name() + " deletes:");
            plan.items().forEach(r -> out.println("  " + (r.data() ? "! " : "- ") + r.what()));
            if (!cloned) {
                out.println("It keeps " + root + " and its files: you opened that folder, so it stays.");
            }
            plan.kept().forEach(k -> out.println("It keeps " + k + "."));
            if (plan.losesData()) {
                out.println("! = data goes too (database contents, changes in the downloaded folder). It can't be"
                        + " undone.");
            }
            if (!yes) {
                out.print("Remove it? [y/N] ");
                out.flush();
                String answer = in.readLine();
                if (answer == null || !answer.strip().equalsIgnoreCase("y")) {
                    out.println("Nothing was removed.");
                    return 1;
                }
            }
            var report = removal.remove(project, cloned, plan);
            report.failed().forEach(f -> out.println("  ✗ " + f));
            if (report.clean()) {
                out.println("Removed " + report.removed().size() + " thing"
                        + (report.removed().size() == 1 ? "" : "s") + ". Checked again: nothing of " + project.name()
                        + " is left.");
                return 0;
            }
            out.println("Checked again: still there:");
            report.leftovers().forEach(r -> out.println("  - " + r.what()));
            return 1;
        }
    }
}
