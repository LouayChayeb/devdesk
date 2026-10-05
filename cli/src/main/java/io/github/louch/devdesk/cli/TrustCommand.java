package io.github.louch.devdesk.cli;

import io.github.louch.devdesk.linux.trust.TrustStore;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** {@code devdesk trust}: shows what a project would run, and remembers the user's yes. */
@Command(name = "trust", description = "Show every command this project runs, and allow DevDesk to run them.")
final class TrustCommand implements Callable<Integer> {

    @Option(
            names = {"-f", "--file"},
            defaultValue = "devdesk.yaml",
            description = "The project file.")
    Path file;

    @Option(names = "--yes", description = "Don't ask; trust it (for scripts that already checked the file).")
    boolean yes;

    private final Map<String, String> env;
    private final TrustStore trust;
    private final BufferedReader in;
    private final PrintStream out;
    private final PrintStream err;

    TrustCommand(Map<String, String> env, TrustStore trust, BufferedReader in, PrintStream out, PrintStream err) {
        this.env = env;
        this.trust = trust;
        this.in = in;
        this.out = out;
        this.err = err;
    }

    @Override
    public Integer call() throws IOException {
        Optional<ProjectFile> loaded = ProjectFile.load(file, env, err);
        if (loaded.isEmpty()) {
            return ProjectFile.INVALID;
        }
        ProjectFile project = loaded.get();
        out.println("Project \"" + project.project().name() + "\" (" + project.path() + ") will run:");
        project.printCommands(out);
        if (!yes) {
            out.print("Trust this project and let DevDesk run these commands? [y/N] ");
            out.flush();
            String answer = in.readLine();
            if (answer == null || !answer.strip().equalsIgnoreCase("y")) {
                out.println("Not trusted. Nothing was changed.");
                return 1;
            }
        }
        trust.trust(project.path(), TrustStore.fingerprint(project.content()));
        out.println("Trusted. If " + project.path().getFileName() + " changes, DevDesk will ask again.");
        return 0;
    }
}
