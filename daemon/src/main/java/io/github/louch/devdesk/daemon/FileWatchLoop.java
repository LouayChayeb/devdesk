package io.github.louch.devdesk.daemon;

import io.github.louch.devdesk.core.project.ProjectSpec;
import io.github.louch.devdesk.core.project.ServiceSpec;
import io.github.louch.devdesk.core.project.Workload;
import io.github.louch.devdesk.core.state.ServiceState;
import io.github.louch.devdesk.core.watch.FileTree;
import io.github.louch.devdesk.core.watch.RestartOnChange;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "Restart when files change": once a second, looks at the watched services' files; when a running service's files
 * changed and then stayed quiet for half a second, restarts it and says why in its logs. On/off per service comes from
 * devdesk.yaml's {@code watch:} and can be switched in the app (until the project is opened again).
 */
final class FileWatchLoop {

    /** A watched folder with more files than this is a mistake (dependencies not skipped): it isn't watched. */
    static final int FILE_LIMIT = 20_000;

    private static final Set<String> RUNNING = Set.of("running", "healthy", "unhealthy");

    private final EngineSession session;
    private final FileTree files;
    private final RestartOnChange decide;
    /** The app's switches: service → on/off, for the project with this key. Cleared when another project opens. */
    private final Map<String, Boolean> switches = new ConcurrentHashMap<>();

    private volatile String switchesFor = "";
    private final Set<String> warnedTooBig = ConcurrentHashMap.newKeySet();

    FileWatchLoop(EngineSession session, FileTree files, Clock clock) {
        this.session = session;
        this.files = files;
        this.decide = new RestartOnChange(clock, Duration.ofMillis(500));
    }

    void start() {
        Thread.ofVirtual().name("file-watch").start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    session.checkBranch(); // one stack per git branch: a checkout swaps the data
                    tick();
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                } catch (RuntimeException e) {
                    // one bad look (a folder vanished mid-walk) must not end watching for good
                }
            }
        });
    }

    /** Which services are watched now, and which folders. */
    Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        session.current()
                .ifPresent(c -> c.project()
                        .services()
                        .values()
                        .forEach(s -> out.put(
                                s.name(),
                                Map.of(
                                        "on", watching(c.project(), s),
                                        "folders",
                                                folders(s).stream()
                                                        .map(f -> relative(c.project(), f))
                                                        .toList()))));
        return out;
    }

    /** The app's switch for one service. Containers aren't watched: their code is inside the image. */
    EngineSession.Answer set(String service, boolean on) {
        var current = session.current();
        if (current.isEmpty() || !current.get().project().services().containsKey(service)) {
            return new EngineSession.Answer(
                    404, ApiJson.error("no service called " + service + " in the open project"));
        }
        ProjectSpec project = current.get().project();
        if (on && project.services().get(service).workload() instanceof Workload.Container) {
            return new EngineSession.Answer(
                    400,
                    ApiJson.error(
                            service + " is a container: its code is in the image, so "
                                    + "file changes on this machine don't reach it. Watch the service that runs your code instead"));
        }
        forProject(project).put(service, on);
        return new EngineSession.Answer(200, status());
    }

    private void tick() {
        var current = session.current();
        if (current.isEmpty()) {
            decide.tick(Map.of());
            return;
        }
        ProjectSpec project = current.get().project();
        Map<String, Map<Path, Long>> looks = new LinkedHashMap<>();
        for (ServiceSpec s : project.services().values()) {
            if (!watching(project, s)) {
                continue;
            }
            Map<Path, Long> seen = files.scan(folders(s), FILE_LIMIT);
            if (seen.size() >= FILE_LIMIT) {
                if (warnedTooBig.add(project.key() + "/" + s.name())) {
                    session.note(
                            s.name(),
                            "not watching for changes: more than " + FILE_LIMIT + " files in " + folders(s)
                                    + ". Point watch: at your source folder (e.g. watch: [src])");
                }
                continue;
            }
            looks.put(s.name(), seen);
        }
        for (RestartOnChange.Restart r : decide.tick(looks)) {
            ServiceState state = current.get().states().getOrDefault(r.service(), new ServiceState.Stopped());
            if (!RUNNING.contains(ApiJson.state(state).get("label").toString())) {
                continue; // not running: the next start picks the changes up anyway
            }
            List<String> names =
                    r.changed().stream().map(p -> relative(project, p)).limit(3).toList();
            int more = r.changed().size() - names.size();
            session.note(
                    r.service(),
                    r.changed().size() + " file" + (r.changed().size() == 1 ? "" : "s")
                            + " changed (" + String.join(", ", names) + (more > 0 ? " and " + more + " more" : "")
                            + "): restarting");
            session.serviceAction(r.service(), "reload");
        }
    }

    /** "api.py", "src", or "." for the project folder itself. */
    private static String relative(ProjectSpec project, Path file) {
        String r = project.root().relativize(file).toString();
        return r.isEmpty() ? "." : r;
    }

    private boolean watching(ProjectSpec project, ServiceSpec s) {
        Boolean on = forProject(project).get(s.name());
        return on != null ? on : !s.watch().isEmpty();
    }

    /** devdesk.yaml's folders, or the service's own folder when switched on in the app. */
    private static List<Path> folders(ServiceSpec s) {
        return s.watch().isEmpty() ? List.of(s.directory()) : s.watch();
    }

    private Map<String, Boolean> forProject(ProjectSpec project) {
        if (!project.key().equals(switchesFor)) {
            switches.clear(); // switches belong to one project: another one starts from its devdesk.yaml
            switchesFor = project.key();
        }
        return switches;
    }
}
