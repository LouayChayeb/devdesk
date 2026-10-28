package io.github.louch.devdesk.cli;

import io.github.louch.devdesk.core.get.GitLink;
import io.github.louch.devdesk.core.project.ProjectSpec;
import io.github.louch.devdesk.core.setup.ExistingSetup;
import io.github.louch.devdesk.core.setup.FirstRun;
import io.github.louch.devdesk.core.setup.FolderScan;
import io.github.louch.devdesk.core.setup.FreePorts;
import io.github.louch.devdesk.core.setup.FreshSetup;
import io.github.louch.devdesk.core.setup.SetupYaml;
import io.github.louch.devdesk.docker.PullLog;
import io.github.louch.devdesk.linux.get.GitClone;
import io.github.louch.devdesk.linux.project.DiskFolder;
import io.github.louch.devdesk.linux.project.ProjectLoader;
import io.github.louch.devdesk.linux.trust.TrustStore;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code devdesk get <link|folder>}: "Get it running" in a terminal. Clones the project (or takes a folder), proposes a
 * devdesk.yaml, asks for trust, runs the setup steps one by one, then starts it. Every step is shown before it runs.
 */
@Command(
        name = "get",
        description = "Get a project running: clone it from a link (or use a folder), set it up, start it.")
final class GetCommand implements Callable<Integer> {

    @Parameters(
            index = "0",
            paramLabel = "LINK-OR-FOLDER",
            description = "https://github.com/owner/project, or a folder")
    String target;

    @Option(names = "--into", description = "Where a cloned project goes (default: ~/DevDesk).")
    Path into;

    @Option(names = "--yes", description = "Don't ask: save, trust and run every step (for scripts and tests).")
    boolean yes;

    @Option(names = "--no-start", description = "Set it up, but don't start it.")
    boolean noStart;

    private final Map<String, String> env;
    private final TrustStore trust;
    private final GitClone clones;
    private final BufferedReader in;
    private final PrintStream out;
    private final PrintStream err;
    private final UpCommand up;

    GetCommand(
            Map<String, String> env,
            TrustStore trust,
            BufferedReader in,
            PrintStream out,
            PrintStream err,
            UpCommand up) {
        this.env = env;
        this.trust = trust;
        this.clones = new GitClone(env, GitClone.defaultLog(env), Clock.systemUTC());
        this.in = in;
        this.out = out;
        this.err = err;
        this.up = up;
    }

    @Override
    public Integer call() throws Exception {
        Optional<Path> folder = folder();
        if (folder.isEmpty()) {
            return 1;
        }
        Path root = folder.get();
        Path file = root.resolve("devdesk.yaml");
        if (!Files.exists(file) && !propose(root, file)) {
            return 1;
        }
        // Checked as Set up will leave it: an env file it makes from an example doesn't exist yet, and that's fine.
        String content = Files.readString(file);
        var project = switch (ProjectLoader.loadForSetup(root, content, env)) {
            case ProjectLoader.Result.Loaded l -> l.project();
            case ProjectLoader.Result.Invalid i -> {
                err.println(i.message());
                yield null;
            }
            case ProjectLoader.Result.Missing m -> {
                err.println("devdesk: " + m.message());
                yield null;
            }
        };
        if (project == null) {
            return ProjectFile.INVALID;
        }
        if (!trust.isTrusted(file, TrustStore.fingerprint(content))) {
            out.println();
            out.println("Project \"" + project.name() + "\" will run:");
            ProjectLoader.commands(project).forEach(line -> out.println("  " + line));
            if (!ask("Trust this project and let DevDesk run these commands?", false)) {
                out.println("Not trusted. Nothing was run.");
                return 1;
            }
            trust.trust(file, TrustStore.fingerprint(content));
        }
        if (!setUp(root, content, project)) {
            return 1;
        }
        if (ProjectFile.load(file, env, err).isEmpty()) {
            return ProjectFile.INVALID; // still something missing after Set up: the message says what
        }
        if (noStart) {
            out.println("Set up. Start it with: cd " + root + " && devdesk up");
            return 0;
        }
        if (!ask("Start it now?", true)) {
            out.println("Start it later with: cd " + root + " && devdesk up");
            return 0;
        }
        up.file = file;
        return up.call();
    }

    /** The folder given, or the folder the link was cloned into. */
    private Optional<Path> folder() throws InterruptedException {
        Path asFolder = Path.of(target.replaceFirst("^~", env.getOrDefault("HOME", "~")));
        if (Files.isDirectory(asFolder)) {
            return Optional.of(asFolder.toAbsolutePath().normalize());
        }
        GitLink link;
        switch (GitLink.parse(target)) {
            case GitLink.Parsed.Refused r -> {
                err.println("devdesk: " + r.why());
                return Optional.empty();
            }
            case GitLink.Parsed.Ok ok -> link = ok.link();
        }
        Path parent = into != null ? into.toAbsolutePath().normalize() : GitClone.defaultParent(env);
        return switch (clones.clone(link, parent, line -> out.println("  " + line))) {
            case GitClone.Result.Cloned c -> {
                out.println("Downloaded into " + c.folder());
                yield Optional.of(c.folder());
            }
            case GitClone.Result.Failed f -> {
                err.println("devdesk: " + f.why());
                yield Optional.empty();
            }
        };
    }

    /** A devdesk.yaml drafted from the folder, shown before it's saved. */
    private boolean propose(Path root, Path file) throws IOException {
        var scan = FolderScan.scan(new DiskFolder(root), root.toString(), List.of());
        var draft = FreePorts.apply(scan.draft(), DiskFolder::portFree);
        // The framework's first command is a suggestion: asked for on its own, never added silently.
        for (var card : FirstRun.suggested(draft)) {
            out.println();
            out.println(card.notes().isEmpty() ? card.origin() : card.notes().getFirst());
            if (!ask("Add \"" + card.command() + "\" as the one-time command " + card.name() + "?", true)) {
                draft = FirstRun.without(draft, card.name());
            }
        }
        if (draft.services().isEmpty()) {
            err.println("devdesk: found nothing to run in " + root
                    + " (no docker-compose.yml, Dockerfile, or app DevDesk knows). Write a devdesk.yaml by hand, or"
                    + " open the folder in the app");
            return false;
        }
        out.println();
        out.println("Found:");
        scan.found().forEach(f -> out.println("  " + f));
        String yaml = SetupYaml.write(draft);
        out.println();
        out.println("Proposed devdesk.yaml:");
        yaml.lines().forEach(l -> out.println("  " + l));
        draft.services().forEach(s -> s.notes().forEach(n -> out.println("  note (" + s.name() + "): " + n)));
        draft.notes().forEach(n -> out.println("  note: " + n));
        if (!ask("Save it as " + file + "?", false)) {
            out.println("Not saved. Nothing was run.");
            return false;
        }
        Files.writeString(file, yaml, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        return true;
    }

    /** The same setup steps as the app's: each shown, then run on a yes. Jobs (migrations) run on the first up. */
    private boolean setUp(Path root, String content, ProjectSpec project)
            throws IOException, InterruptedException, io.github.louch.devdesk.core.config.ConfigException {
        var folder = new DiskFolder(root);
        var plan = FreshSetup.of(
                ExistingSetup.read(content, project.name()),
                folder::read,
                f -> Files.exists(root.resolve(f).normalize()));
        List<String> failed = new ArrayList<>();
        for (FreshSetup.Step step : plan.steps()) {
            switch (step) {
                case FreshSetup.Step.Run r -> {
                    out.println();
                    out.println(r.title() + ":  " + r.command());
                    if (!ask("Run it?", true)) {
                        continue;
                    }
                    String image = r.id().startsWith("pull:") ? r.id().substring("pull:".length()) : "";
                    boolean hadImage =
                            !image.isEmpty() && run(root, "docker image inspect " + image + " >/dev/null 2>&1");
                    if (!run(root.resolve(r.directory()), r.command())) {
                        // The other steps don't depend on this one: go on, and say what failed at the end.
                        failed.add(r.command());
                        continue;
                    }
                    if (!image.isEmpty() && !hadImage) {
                        // Downloaded now, for this project: Remove may delete it. An image you had stays yours.
                        new PullLog(Optional.of(PullLog.defaultFile(env))).record(image, project.key());
                    }
                }
                case FreshSetup.Step.CreateEnv e -> {
                    out.println();
                    out.println(e.title());
                    Map<String, String> values = answers(e.questions());
                    Path env = root.resolve(e.file());
                    Files.writeString(
                            env,
                            FreshSetup.envContent(e.example(), values),
                            StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE_NEW);
                    Files.setPosixFilePermissions(env, PosixFilePermissions.fromString("rw-------"));
                }
                case FreshSetup.Step.FillEnv f -> {
                    out.println();
                    out.println(f.title() + " (Enter keeps the value that's there)");
                    Map<String, String> values = new LinkedHashMap<>();
                    answers(f.questions()).forEach((k, v) -> {
                        if (!v.isBlank()) {
                            values.put(k, v);
                        }
                    });
                    if (!values.isEmpty()) {
                        Path env = root.resolve(f.file());
                        Files.writeString(env, FreshSetup.envContent(Files.readString(env), values));
                    }
                }
                case FreshSetup.Step.Job j -> out.println(j.title() + ": runs before what needs it, when it starts");
            }
        }
        if (!failed.isEmpty()) {
            err.println(
                    "devdesk: " + failed.size() + " step" + (failed.size() == 1 ? "" : "s") + " failed (the output is"
                            + " above): " + String.join("; ", failed) + ". Fix " + (failed.size() == 1 ? "it" : "them")
                            + ", then run devdesk get " + root + " again");
            return false;
        }
        return true;
    }

    private Map<String, String> answers(List<FreshSetup.Question> questions) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        for (FreshSetup.Question q : questions) {
            if (yes) {
                values.put(q.key(), ""); // never invent a secret: it stays empty, and the summary says so
                out.println("  " + q.key() + ": left empty");
                continue;
            }
            out.print("  " + q.key() + (q.example().isBlank() ? "" : " (now: " + q.example() + ")") + ": ");
            out.flush();
            String answer = in.readLine();
            values.put(q.key(), answer == null ? "" : answer.strip());
        }
        return values;
    }

    /** Runs a setup command with its output live; nobody answers prompts, so a tool asking one fails, not hangs. */
    private boolean run(Path directory, String command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", command)
                .directory(directory.toFile())
                .redirectErrorStream(true);
        pb.environment().clear();
        pb.environment().putAll(env);
        pb.environment().put("CI", "true"); // installers then print plain lines instead of animated bars
        Process p = pb.start();
        p.getOutputStream().close();
        try (var lines = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            for (String line = lines.readLine(); line != null; line = lines.readLine()) {
                out.println("  " + line);
            }
        }
        return p.waitFor() == 0;
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
