package io.github.louch.devdesk.core.setup;

import io.github.louch.devdesk.core.container.ContainerRuntime;
import io.github.louch.devdesk.core.data.DatabaseTargets;
import io.github.louch.devdesk.core.mail.MailCatchers;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The wizard's first step: looks at a folder without a devdesk.yaml and drafts one. Reads docker-compose.yml, the build
 * files of the folder and of its direct subfolders, the .env files, and what Docker is running right now.
 */
public final class FolderScan {

    /** The folder, read through the daemon (core does no file I/O). Paths are relative to the folder. */
    public interface Folder {
        /** The folder's own name, which becomes the project's name. */
        String name();

        /** Direct subfolder names, without hidden ones. */
        List<String> subfolders();

        /** The names of the files directly in the folder. */
        List<String> files();

        /** The file's text, or empty when it's missing, outside the folder, too big or not text. */
        Optional<String> read(String relativePath);
    }

    /**
     * A container Docker is running now.
     *
     * @param ports "hostIp:hostPort:containerPort"
     * @param environment its variables, from docker inspect (used to copy a hand-started database's settings)
     * @param volumes "volumeName:/path" for named volumes
     * @param composeFolder the folder docker compose was started in, or ""
     * @param devdesk whether DevDesk started it
     */
    public record RunningContainer(
            String name,
            String image,
            List<String> ports,
            Map<String, String> environment,
            List<String> volumes,
            String composeFolder,
            boolean devdesk) {}

    /**
     * @param draft pre-filled cards
     * @param found one line per thing the scan read ("docker-compose.yml: 4 services"), for the first screen
     * @param suggestions running containers that could become cards (not added unless the user clicks)
     */
    public record Result(SetupDraft draft, List<String> found, List<SetupDraft.Service> suggestions) {}

    static final List<String> COMPOSE_FILES =
            List.of("compose.yaml", "compose.yml", "docker-compose.yml", "docker-compose.yaml");
    private static final Set<String> SKIPPED_FOLDERS =
            Set.of("node_modules", "build", "dist", "target", "out", "vendor", "venv", "coverage", "tmp", "logs");
    private static final List<String> ENV_FILES = List.of(".env", ".env.local", ".env.development");

    private FolderScan() {}

    /**
     * A service's own env files that exist, or that Set up can make from an example next to them (Mastodon's
     * .env.production from .env.production.sample). One with neither is dropped, with a note saying so.
     */
    private static SetupDraft.Service withOwnEnvFiles(SetupDraft.Service s, Folder folder) {
        String files = s.extras().get("env_files");
        if (files == null) {
            return s;
        }
        List<String> kept = new ArrayList<>();
        List<String> notes = new ArrayList<>(s.notes());
        for (String f : files.split(",")) {
            if (folder.read(f).isPresent()) {
                kept.add(f);
            } else if (FreshSetup.exampleOf(f, folder::read).isPresent()) {
                kept.add(f);
                notes.add(f + " doesn't exist yet: Set up makes it from "
                        + FreshSetup.exampleOf(f, folder::read).get()
                        + " and asks for the values that are placeholders.");
            } else {
                notes.add("docker-compose.yml gives it the settings in " + f + ", which doesn't exist and has no"
                        + " example. Create it with what the project's docs say, then open the project again.");
            }
        }
        Map<String, String> extras = new LinkedHashMap<>(s.extras());
        if (kept.isEmpty()) {
            extras.remove("env_files");
        } else {
            extras.put("env_files", String.join(",", kept));
        }
        return new SetupDraft.Service(
                s.name(),
                s.kind(),
                s.command(),
                s.directory(),
                s.image(),
                s.ports(),
                s.volumes(),
                s.environment(),
                s.dependsOn(),
                s.health(),
                s.restart(),
                s.origin(),
                notes,
                extras);
    }

    private static final Pattern EXPOSE = Pattern.compile("(?im)^\\s*EXPOSE\\s+(.+)$");

    /** "EXPOSE 8080 9090/tcp": the TCP ports, in order. UDP and variables ($PORT) can't be published as they are. */
    static List<String> exposedPorts(String dockerfile) {
        List<String> ports = new ArrayList<>();
        Matcher m = EXPOSE.matcher(dockerfile);
        while (m.find()) {
            for (String word : m.group(1).trim().split("\\s+")) {
                String port = word.replaceFirst("/tcp$", "");
                if (port.matches("\\d{1,5}") && !ports.contains(port)) {
                    ports.add(port);
                }
            }
        }
        return ports;
    }

    /** @param absolutePath the folder's full path, to recognise compose containers started from it */
    public static Result scan(Folder folder, String absolutePath, List<RunningContainer> running) {
        String project = projectName(folder.name());
        List<String> found = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        List<SetupDraft.Service> services = new ArrayList<>();
        Set<String> envFiles = new LinkedHashSet<>();

        Optional<String> composeFile =
                COMPOSE_FILES.stream().filter(f -> folder.read(f).isPresent()).findFirst();
        composeFile.ifPresent(file -> {
            // compose reads <name>.override.yml on top of the file by itself; so does DevDesk.
            Optional<String> overrideFile = Stream.of(".override.yml", ".override.yaml")
                    .map(ext -> file.replaceFirst("\\.ya?ml$", ext))
                    .filter(f -> folder.read(f).isPresent())
                    .findFirst();
            var compose = ComposeImport.read(
                    folder.read(file).orElseThrow(),
                    overrideFile.flatMap(folder::read),
                    project,
                    folder.name(),
                    dir -> AppDetector.detectBuild(folder::read, dir));
            services.addAll(compose.services());
            envFiles.addAll(compose.envFiles());
            Optional<String> unreadable = compose.notes().stream()
                    .filter(n -> n.startsWith(ComposeImport.UNREADABLE))
                    .findFirst();
            compose.notes().forEach(n -> notes.add(n.startsWith(ComposeImport.UNREADABLE) ? file + " " + n : n));
            // "0 services" would hide the reason: say it was unreadable, and why, on the first screen.
            found.add(unreadable
                    .map(n -> file + ": " + n)
                    .orElse(file + ": " + compose.services().size() + " service"
                            + (compose.services().size() == 1 ? "" : "s")));
            overrideFile.ifPresent(f -> found.add(f + ": read on top of " + file + ", like docker compose does"));
        });

        // Only a Dockerfile: the project says how it runs, so it runs that way, built by DevDesk.
        if (composeFile.isEmpty()) {
            folder.read("Dockerfile").ifPresent(dockerfile -> {
                List<String> exposed = exposedPorts(dockerfile);
                String compose = "services:\n  " + project + ":\n    build: .\n"
                        + (exposed.isEmpty()
                                ? ""
                                : "    ports: ["
                                        + String.join(
                                                ", ",
                                                exposed.stream()
                                                        .map(port -> "\"" + port + ":" + port + "\"")
                                                        .toList())
                                        + "]\n");
                ComposeImport.read(compose, project, folder.name(), dir -> AppDetector.detectBuild(folder::read, dir))
                        .services()
                        .forEach(card -> services.add(new SetupDraft.Service(
                                card.name(),
                                card.kind(),
                                card.command(),
                                card.directory(),
                                card.image(),
                                card.ports(),
                                card.volumes(),
                                card.environment(),
                                card.dependsOn(),
                                card.health(),
                                card.restart(),
                                "Dockerfile",
                                card.notes(),
                                card.extras())));
                found.add("Dockerfile: built and run as " + project
                        + (exposed.isEmpty() ? "" : " (port " + String.join(", ", exposed) + ")"));
            });
        }

        // Apps the compose file doesn't already cover (compose may only hold the databases).
        // Covered: a folder compose builds an image from, or that runs as a command already. In the trials, adding
        // those again made duplicates on the same port ("umami-2", "order-service-2").
        Set<String> covered = new LinkedHashSet<>();
        services.forEach(s -> {
            if (!s.isContainer()) {
                covered.add(s.directory());
            } else if (s.extras().containsKey("build")) {
                covered.add(s.extras().get("native.directory"));
            }
        });
        Set<Integer> published = new LinkedHashSet<>();
        services.forEach(s -> published.addAll(s.hostPorts()));
        if (composeFile.isPresent()) {
            // A folder named like a compose service is that service's code (Mastodon's streaming/); the top folder
            // is the app itself when a compose service runs the project's own image (Chatwoot: chatwoot/chatwoot).
            services.forEach(s -> covered.add(s.name()));
            String self = folder.name().toLowerCase();
            boolean ownImage = services.stream()
                    .anyMatch(s -> s.isContainer()
                            && (".".equals(s.extras().get("build"))
                                    || s.image()
                                            .replaceFirst(":.*", "")
                                            .replaceFirst(".*/", "")
                                            .equalsIgnoreCase(self)));
            if (ownImage) {
                covered.add("");
            }
        }
        List<String> dirs = new ArrayList<>();
        if (!AppDetector.isWorkspaceRoot(folder::read)) {
            dirs.add("");
        }
        folder.subfolders().stream()
                .filter(d -> !SKIPPED_FOLDERS.contains(d))
                .sorted()
                .forEach(dirs::add);
        for (String dir : dirs) {
            if (covered.contains(dir)) {
                continue;
            }
            AppDetector.detect(folder::read, dir, dir.isEmpty() ? folder.files() : List.of())
                    // An app on a port a compose service already publishes is that service: the official image of
                    // the same app, say.
                    .filter(app -> app.port().filter(published::contains).isEmpty())
                    .ifPresent(app -> {
                        String name = DatabaseTemplates.uniqueName(
                                ComposeImport.serviceName(dir.isEmpty() ? project : dir),
                                new LinkedHashSet<>(services.stream()
                                        .map(SetupDraft.Service::name)
                                        .toList()));
                        List<String> cardNotes = new ArrayList<>(app.notes());
                        services.add(new SetupDraft.Service(
                                name,
                                "command",
                                app.command(),
                                dir,
                                "",
                                app.port().map(p -> List.of(String.valueOf(p))).orElse(List.of()),
                                List.of(),
                                Map.of(),
                                Map.of(),
                                app.healthUrl()
                                        .map(SetupDraft.Health::http)
                                        .orElse(app.port()
                                                .map(SetupDraft.Health::tcp)
                                                .orElse(SetupDraft.Health.PROCESS)),
                                "on-failure",
                                app.origin(),
                                cardNotes));
                        found.add(app.origin() + ": " + app.command());
                    });
        }
        // Plain scripts only when nothing else starts from the top folder (a Node app's helper .py isn't a service).
        if (services.stream()
                .noneMatch(s -> (!s.isContainer() && s.directory().isEmpty())
                        || ".".equals(s.extras().get("build")))) {
            List<SetupDraft.Service> scripts = PythonScripts.find(
                    folder.files(),
                    folder::read,
                    new LinkedHashSet<>(
                            services.stream().map(SetupDraft.Service::name).toList()));
            services.addAll(scripts);
            scripts.forEach(s -> found.add(s.origin() + ": " + s.command()));
        }
        guessDependencies(services);

        // A compose project fills ${VAR} from .env only, as compose does: .env.development (Mastodon's) is for its
        // developers' own setup, not for the containers. Without compose, the usual files of a native app.
        for (String env : composeFile.isPresent() ? List.of(".env") : ENV_FILES) {
            folder.read(env).ifPresent(content -> {
                envFiles.add(env);
                int n = EnvReport.keys(content).size();
                found.add(env + ": " + n + " variable" + (n == 1 ? "" : "s"));
            });
        }
        // .env not there yet, but the compose file uses ${VAR}s nothing sets: Set up creates it and asks for them.
        if (composeFile.isPresent()
                && folder.read(".env").isEmpty()
                && !FreshSetup.unsetVariables(services).isEmpty()) {
            envFiles.add(".env");
        }
        envFiles.removeIf(f -> folder.read(f).isEmpty()
                && !f.equals(".env")
                && FreshSetup.exampleOf(f, folder::read).isEmpty());
        services.replaceAll(s -> withOwnEnvFiles(s, folder));

        List<SetupDraft.Service> suggestions = suggestions(running, absolutePath, services, notes, project);
        if (services.isEmpty()) {
            notes.add(
                    "Nothing to start was recognised here. Add a service by hand, or a database with \"Add database\".");
        }
        // The framework's first command (create the tables), which projects only write in their README.
        SetupDraft draft =
                FirstRun.offer(new SetupDraft(project, List.copyOf(envFiles), services, notes), folder::read);
        return new Result(draft, found, suggestions);
    }

    /**
     * An app found outside the compose file has nothing saying who it needs, so: it waits for every database to be
     * healthy. Most apps crash when their database isn't up yet; the user unticks what's wrong in step 3.
     */
    private static void guessDependencies(List<SetupDraft.Service> services) {
        List<String> databases = services.stream()
                .filter(s -> s.isContainer() && isDataStore(s.image()))
                .map(SetupDraft.Service::name)
                .toList();
        services.replaceAll(s -> {
            if (s.isContainer() || databases.isEmpty() || s.origin().equals("docker-compose.yml")) {
                return s;
            }
            Map<String, String> deps = new LinkedHashMap<>(s.dependsOn());
            databases.forEach(d -> deps.putIfAbsent(d, "healthy"));
            return s.withDependsOn(deps);
        });
    }

    /** A database or mail catcher the Workbench can open: the wizard draws it as one and waits until it's ready. */
    public static boolean isDataStore(String image) {
        return DatabaseTargets.isPostgresImage(image)
                || DatabaseTargets.isMysqlImage(image)
                || DatabaseTargets.isRedisImage(image)
                || DatabaseTargets.isMongoImage(image)
                || MailCatchers.isMailpitImage(image);
    }

    /**
     * Containers started by hand (docker run) become suggestions with their settings and data volume. Compose ones from
     * this folder are already in the draft: they only get a warning that their ports will clash.
     */
    private static List<SetupDraft.Service> suggestions(
            List<RunningContainer> running,
            String absolutePath,
            List<SetupDraft.Service> services,
            List<String> notes,
            String project) {
        List<SetupDraft.Service> out = new ArrayList<>();
        long fromHere = running.stream()
                .filter(c -> c.composeFolder().equals(absolutePath))
                .count();
        if (fromHere > 0) {
            notes.add("docker compose is running this folder right now (" + fromHere + " container"
                    + (fromHere == 1 ? "" : "s") + "). Stop it (docker compose down) before the test run, or the "
                    + "ports will clash.");
        }
        Set<String> names = new LinkedHashSet<>(
                services.stream().map(SetupDraft.Service::name).toList());
        for (RunningContainer c : running) {
            if (c.devdesk() || !c.composeFolder().isEmpty()) {
                continue;
            }
            String name = DatabaseTemplates.uniqueName(ComposeImport.serviceName(baseName(c.image())), names);
            names.add(name);
            String container = ContainerRuntime.containerName(project, name);
            out.add(new SetupDraft.Service(
                    name,
                    "container",
                    "",
                    "",
                    c.image(),
                    c.ports(),
                    c.volumes(),
                    c.environment(),
                    Map.of(),
                    KnownImages.health(c.image(), container, c.ports()).orElse(SetupDraft.Health.PROCESS),
                    "no",
                    "running now as " + c.name(),
                    List.of("It runs now as \"" + c.name() + "\" (started by hand). Stop it before DevDesk starts this "
                            + "one: they use the same ports" + (c.volumes().isEmpty() ? "." : " and the same data."))));
        }
        return out;
    }

    /** "docker.io/library/postgres:16-alpine" → "postgres". */
    private static String baseName(String image) {
        String repo = image.contains("@") ? image.substring(0, image.indexOf('@')) : image;
        int slash = repo.lastIndexOf('/');
        int colon = repo.lastIndexOf(':');
        return repo.substring(slash + 1, colon > slash ? colon : repo.length());
    }

    /** The folder's name as a project name: what people already call it. */
    static String projectName(String folderName) {
        String n = folderName.toLowerCase().replaceAll("[^a-z0-9_.-]", "-").replaceAll("^[-.]+|[-.]+$", "");
        return n.isEmpty() ? "project" : n;
    }
}
