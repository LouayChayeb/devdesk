package io.github.louch.devdesk.daemon;

import io.github.louch.devdesk.core.get.GitLink;
import io.github.louch.devdesk.core.project.ProjectSpec;
import io.github.louch.devdesk.core.removal.ProjectRemoval;
import io.github.louch.devdesk.core.removal.Removable;
import io.github.louch.devdesk.core.removal.RemovalPort;
import io.github.louch.devdesk.docker.DockerConnection;
import io.github.louch.devdesk.linux.get.GitClone;
import io.github.louch.devdesk.linux.process.GroupLeftovers;
import io.github.louch.devdesk.linux.process.ProcessLeftovers;
import io.github.louch.devdesk.linux.process.ScopeLeftovers;
import io.github.louch.devdesk.linux.removal.LocalRemoval;
import io.github.louch.devdesk.linux.trust.TrustStore;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * "Get it running" and "Remove", the engine side: clones a project from a link (the app then opens the setup wizard
 * on the folder), and deletes everything DevDesk made for the open project, after showing the list.
 */
final class GetWorkbench {

    private final EngineSession session;
    private final Supplier<DockerConnection.Result> docker;
    private final Map<String, String> environment;
    private final GitClone clones;

    /** The clone in progress or last finished: the app polls it. */
    private final List<String> lines = new ArrayList<>();

    private String state = "idle";
    private String folder = "";
    private String error = "";

    GetWorkbench(EngineSession session, Supplier<DockerConnection.Result> docker, Map<String, String> environment) {
        this.session = session;
        this.docker = docker;
        this.environment = environment;
        this.clones = new GitClone(environment, GitClone.defaultLog(environment), Clock.systemUTC());
    }

    /** Starts cloning {@code {"link", "parent"?}} in the background; {@link #status()} follows it. */
    synchronized EngineSession.Answer start(Map<?, ?> body) {
        if (!(body.get("link") instanceof String text)) {
            return new EngineSession.Answer(
                    400, ApiJson.error("send {\"link\": \"https://github.com/owner/project\"}"));
        }
        if (state.equals("cloning")) {
            return new EngineSession.Answer(409, ApiJson.error("a project is being downloaded already: wait for it"));
        }
        GitLink link;
        switch (GitLink.parse(text)) {
            case GitLink.Parsed.Refused r -> {
                return new EngineSession.Answer(400, ApiJson.error(r.why()));
            }
            case GitLink.Parsed.Ok ok -> link = ok.link();
        }
        Path parent = body.get("parent") instanceof String p && !p.isBlank()
                ? Path.of(p)
                : GitClone.defaultParent(environment);
        if (!parent.isAbsolute()) {
            return new EngineSession.Answer(400, ApiJson.error("the folder to download into must be a full path"));
        }
        lines.clear();
        state = "cloning";
        folder = "";
        error = "";
        Thread.ofVirtual().name("get-" + link.name()).start(() -> clone(link, parent));
        return status();
    }

    private void clone(GitLink link, Path parent) {
        GitClone.Result result;
        try {
            result = clones.clone(link, parent, this::line);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result = new GitClone.Result.Failed("interrupted");
        }
        synchronized (this) {
            switch (result) {
                case GitClone.Result.Cloned c -> {
                    state = "done";
                    folder = c.folder().toString();
                }
                case GitClone.Result.Failed f -> {
                    state = "failed";
                    error = f.why();
                }
            }
        }
    }

    private synchronized void line(String line) {
        lines.add(line);
        if (lines.size() > 200) {
            lines.removeFirst();
        }
    }

    synchronized EngineSession.Answer status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", state);
        m.put("lines", List.copyOf(lines));
        m.put("folder", folder);
        m.put("error", error);
        return new EngineSession.Answer(200, m);
    }

    // ---- Remove ----

    /** What "Remove" would delete for the open project, shown before anything is. */
    EngineSession.Answer removalPlan() {
        Optional<ProjectSpec> project = session.openProject();
        if (project.isEmpty()) {
            return new EngineSession.Answer(404, ApiJson.error("no project is open"));
        }
        boolean cloned = clones.cloned(project.get().root());
        var plan = removal().plan(project.get(), cloned);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("project", project.get().name());
        m.put("folder", project.get().root().toString());
        m.put("cloned", cloned);
        m.put("items", items(plan.items()));
        m.put("losesData", plan.losesData());
        List<String> kept = new ArrayList<>(plan.kept());
        if (!cloned) {
            kept.addFirst("the folder " + project.get().root() + " and its files: you opened it, so it stays");
        }
        m.put("kept", kept);
        return new EngineSession.Answer(200, m);
    }

    /** Stops the project, deletes everything in a fresh plan, then reports what a fresh look still finds. */
    EngineSession.Answer remove(Map<?, ?> body) {
        if (!Boolean.TRUE.equals(body.get("confirm"))) {
            return new EngineSession.Answer(400, ApiJson.error("send {\"confirm\": true} after showing the list"));
        }
        Optional<ProjectSpec> project = session.openProject();
        if (project.isEmpty()) {
            return new EngineSession.Answer(404, ApiJson.error("no project is open"));
        }
        Optional<String> stopping = session.stopAndClose();
        if (stopping.isPresent()) {
            return new EngineSession.Answer(409, ApiJson.error(stopping.get()));
        }
        boolean cloned = clones.cloned(project.get().root());
        var removal = removal();
        var report = removal.remove(project.get(), cloned, removal.plan(project.get(), cloned));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("removed", items(report.removed()));
        m.put("failed", report.failed());
        m.put("leftovers", items(report.leftovers()));
        m.put("clean", report.clean());
        return new EngineSession.Answer(200, m);
    }

    private ProjectRemoval removal() {
        List<RemovalPort> ports = new ArrayList<>();
        Optional<LocalRemoval.RootFiles> rootFiles = Optional.empty();
        if (docker.get() instanceof DockerConnection.Result.Connected c) {
            ports.add(c.runtime().removal());
            rootFiles = Optional.of(c.runtime()::deleteInside); // a database's files in a clone belong to root
        }
        ports.addFirst(new LocalRemoval(
                new ProcessLeftovers(
                        new ScopeLeftovers(), new GroupLeftovers(GroupLeftovers.defaultFolder(environment))),
                GitClone.defaultLog(environment).getParent(),
                new TrustStore(TrustStore.defaultFile(environment)),
                clones,
                rootFiles));
        return new ProjectRemoval(ports);
    }

    private static List<Map<String, Object>> items(List<Removable> items) {
        return items.stream()
                .map(r -> Map.<String, Object>of(
                        "kind", r.kind().name().toLowerCase(), "what", r.what(), "data", r.data()))
                .toList();
    }
}
