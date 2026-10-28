package io.github.louch.devdesk.cli;

import io.github.louch.devdesk.core.config.ConfigException;
import io.github.louch.devdesk.core.setup.ExistingSetup;
import io.github.louch.devdesk.core.setup.FirstRun;
import io.github.louch.devdesk.core.setup.SetupDraft;
import io.github.louch.devdesk.core.setup.SetupYaml;
import io.github.louch.devdesk.linux.trust.TrustStore;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code devdesk once SERVICE COMMAND...}: the first command a project's README says to run ("rails db:setup"),
 * pasted once. It's saved in devdesk.yaml as a one-time card that runs in a fresh copy of SERVICE, before SERVICE,
 * on each start; then it can run right away.
 */
@Command(
        name = "once",
        description = "Remember a one-time command (like a README's \"rails db:setup\") that runs in a copy of SERVICE"
                + " before it starts.")
final class OnceCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "SERVICE", description = "The service whose image or folder it runs in.")
    String service;

    @Parameters(index = "1..*", arity = "1..*", paramLabel = "COMMAND", description = "The command, as the docs say.")
    List<String> command;

    @Option(
            names = {"-f", "--file"},
            defaultValue = "devdesk.yaml",
            description = "The project file.")
    Path file;

    @Option(names = "--yes", description = "Don't ask: save, trust and start (for scripts and tests).")
    boolean yes;

    @Option(names = "--no-start", description = "Save it, but don't start the project.")
    boolean noStart;

    private final TrustStore trust;
    private final BufferedReader in;
    private final PrintStream out;
    private final PrintStream err;
    private final UpCommand up;

    OnceCommand(TrustStore trust, BufferedReader in, PrintStream out, PrintStream err, UpCommand up) {
        this.trust = trust;
        this.in = in;
        this.out = out;
        this.err = err;
        this.up = up;
    }

    @Override
    public Integer call() throws Exception {
        Path path = file.toAbsolutePath().normalize();
        if (!Files.exists(path)) {
            err.println("devdesk: no " + path + ": set the project up first (devdesk get . or the app's Set up)");
            return ProjectFile.INVALID;
        }
        String folderName = path.getParent().getFileName().toString();
        SetupDraft draft;
        try {
            draft = ExistingSetup.read(Files.readString(path), folderName);
        } catch (ConfigException e) {
            err.println("devdesk: " + path + " has mistakes; fix them first:\n" + e.getMessage());
            return ProjectFile.INVALID;
        }
        List<String> names =
                draft.services().stream().map(SetupDraft.Service::name).toList();
        if (!names.contains(service)) {
            err.println(
                    "devdesk: no service \"" + service + "\" in " + path + ". Services: " + String.join(", ", names));
            return 1;
        }
        String line = String.join(" ", command);
        SetupDraft after = FirstRun.add(draft, service, line, "Pasted with devdesk once.");
        SetupDraft.Service card = after.services().getLast();
        List<String> waiting = after.services().stream()
                .filter(s -> !card.name().equals(s.name())
                        && "completed_successfully".equals(s.dependsOn().get(card.name())))
                .map(SetupDraft.Service::name)
                .toList();
        out.println("Adds the one-time card \"" + card.name() + "\" to " + path + ":");
        out.println("  runs: " + line);
        out.println("  in:   a fresh copy of " + service + " (same " + (card.isContainer() ? "image" : "folder")
                + ", settings and volumes; no ports)");
        out.println("  before: " + String.join(", ", waiting) + ", on each start (so it must be safe to run again)");
        if (!ask("Save it?", false)) {
            out.println("Not saved.");
            return 1;
        }
        String yaml = SetupYaml.write(after);
        Files.writeString(path, yaml, StandardCharsets.UTF_8);
        out.println("Saved. Delete the card in the file (or in Edit setup) to forget it.");
        if (noStart || !ask("Start the project now? " + card.name() + " runs first.", true)) {
            out.println("It runs on the next: devdesk up");
            return 0;
        }
        // The file changed, so the trust given before doesn't cover it: the new command is asked for.
        if (!trust.isTrusted(path, TrustStore.fingerprint(yaml))) {
            if (!ask("Trust the changed devdesk.yaml (it now also runs \"" + line + "\")?", false)) {
                out.println("Not trusted. Nothing was run.");
                return 1;
            }
            trust.trust(path, TrustStore.fingerprint(yaml));
        }
        up.file = path;
        return up.call();
    }

    private boolean ask(String question, boolean yesByDefault) throws IOException {
        if (yes) {
            return true;
        }
        out.print(question + (yesByDefault ? " [Y/n] " : " [y/N] "));
        out.flush();
        String answer = in.readLine();
        if (answer == null || answer.isBlank()) {
            return yesByDefault && answer != null;
        }
        return answer.strip().equalsIgnoreCase("y") || answer.strip().equalsIgnoreCase("yes");
    }
}
