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

/**
 * The wizard's first step: looks at a folder without a devdesk.yaml and drafts one. Reads docker-compose.yml, the build
 * files of the folder and of its direct subfolders, the .env files, and what Docker is running right now.
 */
public final class FolderScan {

    /** The folder, read through the daemon (core does no file I/O). Paths are relative to the folder. */
    public interface Folder {
        String name();

        /** Direct subfolder names, without hidden ones. */
        List<String> subfolders();

        /** The names of the files directly in the folder. */
        List<String> files();

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
            var compose = ComposeImport.read(
                    folder.read(file).orElseThrow(),
                    project,
                    folder.name(),
                    dir -> AppDetector.detect(folder::read, dir));
            services.addAll(compose.services());
            envFiles.addAll(compose.envFiles());
            notes.addAll(compose.notes());
            found.add(file + ": " + compose.services().size() + " service"
                    + (compose.services().size() == 1 ? "" : "s"));
            if (folder.read(file.replaceFirst("\\.ya?ml$", ".override.yml")).isPresent()) {
                notes.add("docker-compose.override.yml was not read: check its changes by hand.");
            }
        });

        // Apps the compose file doesn't already cover (compose may only hold the databases).
        Set<String> covered = new LinkedHashSet<>();
        services.stream().filter(s -> !s.isContainer()).forEach(s -> covered.add(s.directory()));
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
            AppDetector.detect(folder::read, dir).ifPresent(app -> {
                String name = DatabaseTemplates.uniqueName(
                        ComposeImport.serviceName(dir.isEmpty() ? project : dir),
                        new LinkedHashSet<>(
                                services.stream().map(SetupDraft.Service::name).toList()));
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
                                .orElse(app.port().map(SetupDraft.Health::tcp).orElse(SetupDraft.Health.PROCESS)),
                        "on-failure",
                        app.origin(),
                        cardNotes));
                found.add(app.origin() + ": " + app.command());
            });
        }
        // Plain scripts only when nothing else starts from the top folder (a Node app's helper .py isn't a service).
        if (services.stream().noneMatch(s -> !s.isContainer() && s.directory().isEmpty())) {
            List<SetupDraft.Service> scripts = PythonScripts.find(
                    folder.files(),
                    folder::read,
                    new LinkedHashSet<>(
                            services.stream().map(SetupDraft.Service::name).toList()));
            services.addAll(scripts);
            scripts.forEach(s -> found.add(s.origin() + ": " + s.command()));
        }
        guessDependencies(services);

        for (String env : ENV_FILES) {
            folder.read(env).ifPresent(content -> {
                envFiles.add(env);
                int n = EnvReport.keys(content).size();
                found.add(env + ": " + n + " variable" + (n == 1 ? "" : "s"));
            });
        }
        envFiles.removeIf(f -> folder.read(f).isEmpty()); // devdesk.yaml refuses an env file that doesn't exist

        List<SetupDraft.Service> suggestions = suggestions(running, absolutePath, services, notes, project);
        if (services.isEmpty()) {
            notes.add(
                    "Nothing to start was recognised here. Add a service by hand, or a database with \"Add database\".");
        }
        return new Result(new SetupDraft(project, List.copyOf(envFiles), services, notes), found, suggestions);
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
