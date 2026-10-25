package io.github.louch.devdesk.daemon;

import io.github.louch.devdesk.core.data.DataException;
import io.github.louch.devdesk.core.data.DatabaseTarget;
import io.github.louch.devdesk.core.data.KeyValueBrowser;
import io.github.louch.devdesk.core.data.RedisCommandLine;
import io.github.louch.devdesk.core.data.SchemaDiagram;
import io.github.louch.devdesk.core.data.SqlBrowser;
import io.github.louch.devdesk.core.data.SqlEditor;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.json.JsonMapper;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The engine's local HTTP + WebSocket API for the desktop app. Listens on 127.0.0.1 only.
 *
 * <p>Three locks on the door, because any website open in your browser can also send requests to 127.0.0.1:
 * <ol>
 *   <li><b>Token</b>: a random secret only the app knows; every request must carry it.
 *   <li><b>Host check</b>: the Host header must be 127.0.0.1 or localhost, which defeats "DNS rebinding" (a website
 *       making its own domain point at 127.0.0.1).
 *   <li><b>Origin check</b>: browsers say which page a request comes from; only the app's own pages are allowed.
 * </ol>
 */
final class ApiServer {

    /** The desktop app's page origins (Tauri on Linux, and the dev servers while developing the UI). */
    static final Set<String> ALLOWED_ORIGINS = Set.of(
            "tauri://localhost",
            "http://tauri.localhost",
            "https://tauri.localhost",
            "http://localhost:1420",
            "http://127.0.0.1:1420",
            "http://localhost:4200",
            "http://127.0.0.1:4200");

    private final EngineSession session;
    private final HttpWorkbench requests;
    private final DataWorkbench data;
    private final RedisWatches watches;
    private final MailWorkbench mail;
    private final SocketSessions sockets;
    private final MockWorkbench mocks;
    private final SetupWorkbench setup;
    private final EnvWorkbench envFiles;
    private final TaskRunner tasks;
    private final TaskRunner tests;
    private final SnapshotWorkbench snapshots;
    private final MigrationCheck migrations;
    private final QueueWorkbench queues;
    private final ChangeTracker changes;
    private final CrashBundle bundle;
    private final TrafficRecorder recorder;
    private final StressLab stress;
    private final RemoteWorkbench remote;
    private final FileWatchLoop watch;
    private final TerminalSockets terminals;
    /** The extras' routes are registered only when on (see {@link Extras}). */
    private final boolean extras;

    private final Map<String, String> environment;
    private final byte[] token;
    private final Map<String, EventClient> clients = new ConcurrentHashMap<>();
    private Javalin app;

    ApiServer(
            EngineSession session,
            HttpWorkbench requests,
            DataWorkbench data,
            RedisWatches watches,
            MailWorkbench mail,
            SocketSessions sockets,
            MockWorkbench mocks,
            SetupWorkbench setup,
            EnvWorkbench envFiles,
            TaskRunner tasks,
            TaskRunner tests,
            SnapshotWorkbench snapshots,
            MigrationCheck migrations,
            QueueWorkbench queues,
            ChangeTracker changes,
            CrashBundle bundle,
            TrafficRecorder recorder,
            StressLab stress,
            RemoteWorkbench remote,
            FileWatchLoop watch,
            TerminalSockets terminals,
            boolean extras,
            Map<String, String> environment,
            String token) {
        this.session = session;
        this.requests = requests;
        this.data = data;
        this.watches = watches;
        this.mail = mail;
        this.sockets = sockets;
        this.mocks = mocks;
        this.setup = setup;
        this.envFiles = envFiles;
        this.tasks = tasks;
        this.tests = tests;
        this.snapshots = snapshots;
        this.migrations = migrations;
        this.queues = queues;
        this.changes = changes;
        this.bundle = bundle;
        this.recorder = recorder;
        this.stress = stress;
        this.remote = remote;
        this.watch = watch;
        this.terminals = terminals;
        this.extras = extras;
        this.environment = environment;
        this.token = token.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The extras switch as the app shows it: what runs now, what the settings file says (the next start), and
     * whether DEVDESK_EXTRAS overrides the file.
     */
    private Map<String, Object> settings() {
        return Map.of(
                "extras", extras,
                "extrasSaved", Extras.saved(environment).orElse(false),
                "extrasFromEnvironment", Extras.forcedByEnvironment(environment));
    }

    /** Starts on 127.0.0.1 and a free port chosen by the kernel (port 0). Returns that port. */
    int start() {
        app = Javalin.create(config -> {
            config.jsonMapper(new Jackson3Mapper());
            config.startup.showJavalinBanner = false;
            config.routes.before(this::guard);
            config.routes.options("/*", ctx -> ctx.status(HttpStatus.NO_CONTENT));
            config.routes.get("/api/v1/project", ctx -> answer(ctx, session.project()));
            // App settings: the extras switch (applies after a restart, since routes are registered once).
            config.routes.get("/api/v1/settings", ctx -> ctx.json(settings()));
            config.routes.post("/api/v1/settings/extras", ctx -> {
                if (!(ctx.bodyAsClass(Map.class).get("on") instanceof Boolean on)) {
                    ctx.status(400).json(ApiJson.error("send {\"on\": true} or {\"on\": false}"));
                    return;
                }
                Extras.save(environment, on);
                ctx.json(settings());
            });
            config.routes.post("/api/v1/project/open", ctx -> {
                Map<?, ?> body = ctx.bodyAsClass(Map.class);
                Object path = body.get("path");
                if (!(path instanceof String p) || p.isBlank()) {
                    ctx.status(400).json(ApiJson.error("send {\"path\": \"/folder/with/devdesk.yaml\"}"));
                    return;
                }
                Path folder = Path.of(p.replaceFirst("^~", System.getProperty("user.home")));
                // "stopCurrent": the user chose "stop this one and open that one" in the project switcher.
                answer(
                        ctx,
                        Boolean.TRUE.equals(body.get("stopCurrent")) ? session.switchTo(folder) : session.open(folder));
            });
            config.routes.post("/api/v1/project/trust", ctx -> answer(ctx, session.trust()));
            // The "Add project" wizard: scan a folder, edit cards, check, test-run unsaved, write devdesk.yaml.
            config.routes.post("/api/v1/setup/scan", ctx -> answer(ctx, setup.scan(ctx.bodyAsClass(Map.class))));
            config.routes.post(
                    "/api/v1/setup/database", ctx -> answer(ctx, setup.addDatabase(ctx.bodyAsClass(Map.class))));
            config.routes.post("/api/v1/setup/preview", ctx -> answer(ctx, setup.preview(ctx.bodyAsClass(Map.class))));
            config.routes.post("/api/v1/setup/doctor", ctx -> answer(ctx, setup.doctor(ctx.bodyAsClass(Map.class))));
            config.routes.post("/api/v1/setup/test", ctx -> answer(ctx, setup.test(ctx.bodyAsClass(Map.class))));
            config.routes.post("/api/v1/setup/write", ctx -> answer(ctx, setup.write(ctx.bodyAsClass(Map.class))));
            // "Set up" a fresh clone: plan (shown first), run the chosen steps, follow, cancel.
            config.routes.post("/api/v1/setup/fresh", ctx -> answer(ctx, setup.freshPlan(ctx.bodyAsClass(Map.class))));
            config.routes.post(
                    "/api/v1/setup/fresh/run", ctx -> answer(ctx, setup.freshRun(ctx.bodyAsClass(Map.class))));
            config.routes.get("/api/v1/setup/fresh/run", ctx -> answer(ctx, setup.freshStatus()));
            config.routes.post("/api/v1/setup/fresh/cancel", ctx -> answer(ctx, setup.freshCancel()));
            config.routes.post("/api/v1/setup/discard", ctx -> answer(ctx, setup.discard()));
            config.routes.post(
                    "/api/v1/setup/variable", ctx -> answer(ctx, setup.setVariable(ctx.bodyAsClass(Map.class))));
            config.routes.post("/api/v1/project/up", ctx -> answer(ctx, session.up()));
            config.routes.post("/api/v1/project/down", ctx -> answer(ctx, session.down()));
            config.routes.get("/api/v1/project/why", ctx -> answer(ctx, session.why()));
            // Log search: every line the services printed, kept some days. ?q=text or /regex/&service=a,b&errors=true
            config.routes.get("/api/v1/logs/search", ctx -> {
                String hours = Objects.requireNonNullElse(ctx.queryParam("hours"), "24");
                String services = Objects.requireNonNullElse(ctx.queryParam("service"), "");
                answer(
                        ctx,
                        session.searchLogs(
                                Objects.requireNonNullElse(ctx.queryParam("q"), ""),
                                services.isBlank() ? Set.of() : Set.of(services.split(",")),
                                "true".equals(ctx.queryParam("errors")),
                                Duration.ofHours(hours.matches("\\d{1,3}") ? Integer.parseInt(hours) : 24),
                                limit(ctx.queryParam("limit"), 300)));
            });
            config.routes.get("/api/v1/logs/around", ctx -> {
                try {
                    answer(
                            ctx,
                            session.logsAround(
                                    Objects.requireNonNullElse(ctx.queryParam("service"), ""),
                                    Instant.parse(Objects.requireNonNullElse(ctx.queryParam("at"), ""))));
                } catch (DateTimeParseException e) {
                    ctx.status(400).json(ApiJson.error("send ?service=api&at=2026-10-13T10:00:00Z"));
                }
            });
            config.routes.get("/api/v1/project/doctor", ctx -> answer(ctx, session.doctor()));
            // Restart on code change: which services are watched, and the app's on/off switch per service.
            config.routes.get("/api/v1/watch", ctx -> answer(ctx, new EngineSession.Answer(200, watch.status())));
            config.routes.post("/api/v1/services/{name}/watch", ctx -> {
                Map<?, ?> body = ctx.bodyAsClass(Map.class);
                answer(ctx, watch.set(ctx.pathParam("name"), Boolean.TRUE.equals(body.get("on"))));
            });
            // Tasks: the project's one-off commands (seed, test, reset-db), their runs and output.
            config.routes.get("/api/v1/tasks", ctx -> answer(ctx, tasks.list()));
            config.routes.get("/api/v1/tasks/{name}", ctx -> answer(ctx, tasks.runs(ctx.pathParam("name"))));
            config.routes.post("/api/v1/tasks/{name}/run", ctx -> answer(ctx, tasks.run(ctx.pathParam("name"))));
            config.routes.post("/api/v1/tasks/{name}/cancel", ctx -> answer(ctx, tasks.cancel(ctx.pathParam("name"))));
            // The Tests page: each service's tests (named after the service), run one by one or all in a row.
            // Snapshots ("save game"): every database, Redis and env file of the project, saved and restored together.
            // Flyway, Prisma and Alembic migrations that haven't run on the project's databases.
            config.routes.get("/api/v1/migrations", ctx -> answer(ctx, migrations.check()));
            config.routes.get("/api/v1/snapshots", ctx -> answer(ctx, snapshots.list()));
            config.routes.post("/api/v1/snapshots", ctx -> {
                Object label = ctx.bodyAsClass(Map.class).get("label");
                answer(ctx, snapshots.save(label instanceof String l ? l : ""));
            });
            config.routes.post(
                    "/api/v1/snapshots/{id}/restore",
                    ctx -> answer(
                            ctx,
                            snapshots.restore(
                                    ctx.pathParam("id"),
                                    !Boolean.FALSE.equals(
                                            ctx.bodyAsClass(Map.class).get("saveFirst")))));
            config.routes.post(
                    "/api/v1/snapshots/{id}/delete", ctx -> answer(ctx, snapshots.delete(ctx.pathParam("id"))));
            config.routes.get("/api/v1/tests", ctx -> answer(ctx, tests.list()));
            config.routes.post("/api/v1/tests/run-all", ctx -> answer(ctx, tests.runAll()));
            config.routes.post("/api/v1/tests/cancel-all", ctx -> answer(ctx, tests.cancelAll()));
            config.routes.get("/api/v1/tests/{name}", ctx -> answer(ctx, tests.runs(ctx.pathParam("name"))));
            config.routes.post("/api/v1/tests/{name}/run", ctx -> answer(ctx, tests.run(ctx.pathParam("name"))));
            config.routes.post("/api/v1/tests/{name}/cancel", ctx -> answer(ctx, tests.cancel(ctx.pathParam("name"))));
            // The .env editor: values (secrets hidden until revealed), who uses them, what's missing.
            config.routes.get("/api/v1/env", ctx -> answer(ctx, envFiles.overview()));
            config.routes.post("/api/v1/env/set", ctx -> answer(ctx, envFiles.set(ctx.bodyAsClass(Map.class))));
            config.routes.post("/api/v1/env/delete", ctx -> answer(ctx, envFiles.delete(ctx.bodyAsClass(Map.class))));
            config.routes.post("/api/v1/env/reveal", ctx -> answer(ctx, envFiles.reveal(ctx.bodyAsClass(Map.class))));
            config.routes.post("/api/v1/project/profile", ctx -> {
                Map<?, ?> body = ctx.bodyAsClass(Map.class);
                // "" (or none) = the default: devdesk.yaml's settings without a profile
                answer(ctx, session.selectProfile(body.get("profile") instanceof String p ? p.strip() : ""));
            });
            config.routes.get("/api/v1/project/startups", ctx -> answer(ctx, session.startups()));
            // "It worked yesterday": what changed since the project last started cleanly.
            config.routes.get("/api/v1/project/changes", ctx -> answer(ctx, changes.changes()));
            // The same changes ranked against what fails now: the verdict.
            config.routes.get("/api/v1/project/verdict", ctx -> answer(ctx, changes.verdict()));
            // "Prove it": replays the previous and current image in throwaway containers (up to ~30 s).
            config.routes.post("/api/v1/project/verdict/prove", ctx -> {
                Map<?, ?> body = ctx.bodyAsClass(Map.class);
                if (!(body.get("image") instanceof String image) || image.isBlank()) {
                    answer(
                            ctx,
                            new EngineSession.Answer(
                                    400, ApiJson.error("say which image: {\"image\": \"postgres:latest\"}")));
                    return;
                }
                answer(ctx, changes.prove(image));
            });
            // One zip with states, findings, logs, timeline and settings, secrets taken out.
            config.routes.post("/api/v1/project/bundle", ctx -> answer(ctx, bundle.write(ctx.bodyAsClass(Map.class))));
            if (extras) { // extras: recorder, stress lab, remote machine (see Extras)
                // The traffic recorder: record the calls to a service through a stand-in port, then replay them.
                // The stress lab: climbing load on one URL of this machine, and which service gives up first.
                // Another machine over SSH: deploy the project there, or use it as a remote dev box.
                config.routes.get("/api/v1/remote", ctx -> answer(ctx, remote.status()));
                config.routes.post(
                        "/api/v1/remote/target", ctx -> answer(ctx, remote.setTarget(ctx.bodyAsClass(Map.class))));
                config.routes.post(
                        "/api/v1/remote/run",
                        ctx -> answer(
                                ctx,
                                remote.run(String.valueOf(
                                        ctx.bodyAsClass(Map.class).get("mode")))));
                config.routes.post(
                        "/api/v1/remote/stop",
                        ctx -> answer(
                                ctx,
                                remote.stop(Boolean.TRUE.equals(
                                        ctx.bodyAsClass(Map.class).get("stopRemote")))));
                config.routes.get("/api/v1/stress", ctx -> answer(ctx, stress.current()));
                config.routes.post(
                        "/api/v1/stress/start", ctx -> answer(ctx, stress.start(ctx.bodyAsClass(Map.class))));
                config.routes.post("/api/v1/stress/cancel", ctx -> answer(ctx, stress.cancel()));
                config.routes.get("/api/v1/recorder", ctx -> answer(ctx, recorder.overview()));
                config.routes.post(
                        "/api/v1/recorder/start", ctx -> answer(ctx, recorder.start(ctx.bodyAsClass(Map.class))));
                config.routes.get("/api/v1/recorder/{id}", ctx -> answer(ctx, recorder.calls(ctx.pathParam("id"))));
                config.routes.post(
                        "/api/v1/recorder/{id}/stop", ctx -> answer(ctx, recorder.stop(ctx.pathParam("id"))));
                config.routes.post(
                        "/api/v1/recorder/{id}/replay", ctx -> answer(ctx, recorder.replay(ctx.pathParam("id"))));
                config.routes.post(
                        "/api/v1/recorder/{id}/delete", ctx -> answer(ctx, recorder.delete(ctx.pathParam("id"))));
            }
            config.routes.get("/api/v1/project/history", ctx -> {
                // ?hours=24 by default; at most the 14 days the recorder keeps.
                String hours = ctx.queryParam("hours");
                if (hours != null && !hours.matches("\\d{1,3}")) {
                    ctx.status(400).json(ApiJson.error("hours must be a whole number, like ?hours=24"));
                    return;
                }
                int h = hours == null ? 24 : Math.min(Integer.parseInt(hours), 14 * 24);
                answer(ctx, session.history(Duration.ofHours(h)));
            });
            config.routes.get("/api/v1/project/machine", ctx -> answer(ctx, session.machine()));
            config.routes.get("/api/v1/ports", ctx -> answer(ctx, session.ports()));
            // The Workbench: SQL databases. Browsing is read-only; writes are explicit actions (Save, import, Stop).
            config.routes.get("/api/v1/data", ctx -> answer(ctx, session.databases()));
            config.routes.get(
                    "/api/v1/data/{service}/tables",
                    ctx -> answer(
                            ctx, sql(ctx, t -> ApiJson.tables(session.sql(t).tables(t)))));
            config.routes.get("/api/v1/data/{service}/structure", ctx -> {
                String schema = Objects.requireNonNullElse(ctx.queryParam("schema"), "public");
                String table = Objects.requireNonNullElse(ctx.queryParam("table"), "");
                answer(
                        ctx,
                        sql(
                                ctx,
                                t -> session.sql(t)
                                        .structure(t, schema, table)
                                        .map(ApiJson::structure)
                                        .orElseThrow(
                                                () -> new DataException("there is no table " + schema + "." + table))));
            });
            config.routes.post(
                    "/api/v1/data/{service}/design/preview",
                    ctx -> answer(ctx, data.previewDesign(ctx.pathParam("service"), ctx.bodyAsClass(Map.class))));
            config.routes.post(
                    "/api/v1/data/{service}/design/apply",
                    ctx -> answer(ctx, data.applyDesign(ctx.pathParam("service"), ctx.bodyAsClass(Map.class))));
            config.routes.post(
                    "/api/v1/data/{service}/drop-table",
                    ctx -> answer(ctx, data.dropTable(ctx.pathParam("service"), ctx.bodyAsClass(Map.class))));
            config.routes.get(
                    "/api/v1/data/{service}/query-logging",
                    ctx -> answer(ctx, sql(ctx, t -> {
                        Optional<Boolean> on = session.sqlEditor(t).queryLogging(t);
                        return Map.of("supported", on.isPresent(), "on", on.orElse(false));
                    })));
            config.routes.post("/api/v1/data/{service}/query-logging", ctx -> {
                boolean on = Boolean.TRUE.equals(ctx.bodyAsClass(Map.class).get("on"));
                answer(ctx, sql(ctx, t -> {
                    session.sqlEditor(t).setQueryLogging(t, on);
                    return Map.of("on", on);
                }));
            });
            config.routes.get(
                    "/api/v1/data/{service}/stats",
                    ctx -> answer(
                            ctx, sql(ctx, t -> ApiJson.stats(session.sql(t).stats(t)))));
            config.routes.get("/api/v1/data/{service}/diagram", ctx -> {
                String schema = Objects.requireNonNullElse(ctx.queryParam("schema"), "");
                answer(ctx, sql(ctx, t -> diagram(session.sql(t), t, schema)));
            });
            config.routes.post("/api/v1/data/{service}/rows", ctx -> {
                Optional<SqlBrowser.RowQuery> q = rowQuery(ctx.bodyAsClass(Map.class));
                if (q.isEmpty()) {
                    ctx.status(400)
                            .json(ApiJson.error("send {\"schema\", \"table\", \"orderBy\"?, \"descending\"?, "
                                    + "\"filters\"?: [{\"column\", \"op\", \"value\"}], \"offset\"?, \"limit\"?}"));
                    return;
                }
                answer(ctx, sql(ctx, t -> ApiJson.result(session.sql(t).rows(t, q.get()))));
            });
            config.routes.post("/api/v1/data/{service}/query", ctx -> {
                Map<?, ?> body = ctx.bodyAsClass(Map.class);
                if (!(body.get("sql") instanceof String sql) || sql.isBlank()) {
                    ctx.status(400).json(ApiJson.error("send {\"sql\": \"select ...\"}"));
                    return;
                }
                int max = body.get("maxRows") instanceof Number n ? Math.clamp(n.intValue(), 1, MAX_ROWS) : 500;
                answer(ctx, sql(ctx, t -> ApiJson.result(session.sql(t).run(t, sql, max))));
            });
            config.routes.post("/api/v1/data/{service}/plan", ctx -> {
                Map<?, ?> body = ctx.bodyAsClass(Map.class);
                if (!(body.get("sql") instanceof String sql) || sql.isBlank()) {
                    ctx.status(400).json(ApiJson.error("send {\"sql\": \"select ...\", \"analyze\"?: true}"));
                    return;
                }
                boolean analyze = Boolean.TRUE.equals(body.get("analyze"));
                answer(ctx, sql(ctx, t -> ApiJson.plan(session.sql(t).explain(t, sql, analyze))));
            });
            config.routes.get(
                    "/api/v1/data/{service}/activity",
                    ctx -> answer(
                            ctx, sql(ctx, t -> ApiJson.sessions(session.sql(t).activity(t)))));
            // Stopping another session is an explicit click: KILL QUERY / pg_cancel_backend, or the whole connection.
            config.routes.post("/api/v1/data/{service}/activity/{pid}/stop", ctx -> {
                if (!ctx.pathParam("pid").matches("\\d{1,9}")) {
                    ctx.status(400).json(ApiJson.error("pid must be a number"));
                    return;
                }
                int pid = Integer.parseInt(ctx.pathParam("pid"));
                boolean terminate =
                        Boolean.TRUE.equals(ctx.bodyAsClass(Map.class).get("terminate"));
                answer(ctx, sql(ctx, t -> Map.of("stopped", session.sqlEditor(t).stop(t, pid, terminate))));
            });
            config.routes.post(
                    "/api/v1/data/{service}/export",
                    ctx -> answer(ctx, data.export(ctx.pathParam("service"), ctx.bodyAsClass(Map.class))));
            config.routes.post(
                    "/api/v1/data/{service}/import",
                    ctx -> answer(ctx, data.importFile(ctx.pathParam("service"), ctx.bodyAsClass(Map.class))));
            // Fake test rows: see the plan and examples first, then insert.
            config.routes.post(
                    "/api/v1/data/{service}/fake/preview",
                    ctx -> answer(ctx, data.fakePreview(ctx.pathParam("service"), ctx.bodyAsClass(Map.class))));
            config.routes.post(
                    "/api/v1/data/{service}/fake/insert",
                    ctx -> answer(ctx, data.fakeInsert(ctx.pathParam("service"), ctx.bodyAsClass(Map.class))));
            config.routes.get(
                    "/api/v1/data/{service}/backups", ctx -> answer(ctx, data.backups(ctx.pathParam("service"))));
            config.routes.post(
                    "/api/v1/data/{service}/backups",
                    ctx -> answer(ctx, data.saveBackup(ctx.pathParam("service"), ctx.bodyAsClass(Map.class))));
            config.routes.post(
                    "/api/v1/data/{service}/backups/{name}/restore",
                    ctx -> answer(ctx, data.restoreBackup(ctx.pathParam("service"), ctx.pathParam("name"))));
            config.routes.post(
                    "/api/v1/data/{service}/backups/{name}/delete",
                    ctx -> answer(ctx, data.deleteBackup(ctx.pathParam("service"), ctx.pathParam("name"))));
            if (extras) { // extras: S3 and Elasticsearch pages
                // S3-style file storage: buckets, one folder level at a time, previews, and explicit changes.
                config.routes.get(
                        "/api/v1/data/{service}/objects/buckets",
                        ctx -> answer(ctx, data.buckets(ctx.pathParam("service"))));
                config.routes.post(
                        "/api/v1/data/{service}/objects/buckets",
                        ctx -> answer(ctx, data.createBucket(ctx.pathParam("service"), ctx.bodyAsClass(Map.class))));
                config.routes.get(
                        "/api/v1/data/{service}/objects",
                        ctx -> answer(
                                ctx,
                                data.files(
                                        ctx.pathParam("service"),
                                        Objects.requireNonNullElse(ctx.queryParam("bucket"), ""),
                                        Objects.requireNonNullElse(ctx.queryParam("prefix"), ""),
                                        Optional.ofNullable(ctx.queryParam("after"))
                                                .filter(a -> !a.isEmpty()))));
                config.routes.get(
                        "/api/v1/data/{service}/objects/preview",
                        ctx -> answer(
                                ctx,
                                data.preview(
                                        ctx.pathParam("service"),
                                        Objects.requireNonNullElse(ctx.queryParam("bucket"), ""),
                                        Objects.requireNonNullElse(ctx.queryParam("key"), ""))));
                config.routes.post(
                        "/api/v1/data/{service}/objects/download",
                        ctx -> answer(ctx, data.download(ctx.pathParam("service"), ctx.bodyAsClass(Map.class))));
                config.routes.post(
                        "/api/v1/data/{service}/objects/upload",
                        ctx -> answer(ctx, data.upload(ctx.pathParam("service"), ctx.bodyAsClass(Map.class))));
                config.routes.post(
                        "/api/v1/data/{service}/objects/delete",
                        ctx -> answer(ctx, data.deleteFile(ctx.pathParam("service"), ctx.bodyAsClass(Map.class))));
                // Elasticsearch / OpenSearch: indices, their fields, and searches (read-only).
                config.routes.get(
                        "/api/v1/data/{service}/search/indices",
                        ctx -> answer(ctx, data.indices(ctx.pathParam("service"))));
                config.routes.get(
                        "/api/v1/data/{service}/search/mapping",
                        ctx -> answer(
                                ctx,
                                data.mapping(
                                        ctx.pathParam("service"),
                                        Objects.requireNonNullElse(ctx.queryParam("index"), ""))));
                config.routes.post(
                        "/api/v1/data/{service}/search",
                        ctx -> answer(ctx, data.search(ctx.pathParam("service"), ctx.bodyAsClass(Map.class))));
            }
            // Postgres's own slowest-queries list, and the explicit steps to switch it on or start over.
            config.routes.get(
                    "/api/v1/data/{service}/slow-queries",
                    ctx -> answer(ctx, data.slowQueries(ctx.pathParam("service"))));
            config.routes.post(
                    "/api/v1/data/{service}/slow-queries",
                    ctx -> answer(ctx, data.slowQueriesStep(ctx.pathParam("service"), ctx.bodyAsClass(Map.class))));
            config.routes.get(
                    "/api/v1/data/{service}/queries", ctx -> answer(ctx, data.queries(ctx.pathParam("service"))));
            config.routes.post(
                    "/api/v1/data/{service}/queries",
                    ctx -> answer(ctx, data.saveQuery(ctx.pathParam("service"), ctx.bodyAsClass(Map.class))));
            config.routes.post(
                    "/api/v1/data/{service}/queries/{id}/delete",
                    ctx -> answer(ctx, data.deleteQuery(ctx.pathParam("service"), ctx.pathParam("id"))));
            // The only write to a database: the grid's pending changes, saved together in one transaction.
            config.routes.post("/api/v1/data/{service}/changes", ctx -> {
                Optional<SqlEditor.TableChanges> changes = tableChanges(ctx.bodyAsClass(Map.class));
                if (changes.isEmpty()) {
                    ctx.status(400)
                            .json(ApiJson.error("send {\"schema\", \"table\", \"changes\": [{\"op\": \"insert\", "
                                    + "\"values\"} | {\"op\": \"update\", \"key\", \"values\"} | "
                                    + "{\"op\": \"delete\", \"key\"}]}"));
                    return;
                }
                answer(ctx, sql(ctx, t -> ApiJson.applied(session.sqlEditor(t).apply(t, changes.get()))));
            });
            // The Workbench: Redis. Browsing is read-only; delete, TTL and the command box are explicit actions.
            config.routes.get("/api/v1/data/{service}/keys", ctx -> {
                String pattern = Objects.requireNonNullElse(ctx.queryParam("pattern"), "*");
                String cursor = Objects.requireNonNullElse(ctx.queryParam("cursor"), "0");
                String count = Objects.requireNonNullElse(ctx.queryParam("count"), "500");
                if (!cursor.matches("\\d{1,20}") || !count.matches("\\d{1,5}")) {
                    ctx.status(400).json(ApiJson.error("cursor and count must be whole numbers"));
                    return;
                }
                answer(
                        ctx,
                        redis(
                                ctx,
                                t -> ApiJson.keyPage(
                                        session.redis().keys(t, pattern, cursor, Integer.parseInt(count)))));
            });
            config.routes.get("/api/v1/data/{service}/key", ctx -> {
                String name = ctx.queryParam("name");
                if (name == null) {
                    ctx.status(400).json(ApiJson.error("send ?name=the:key"));
                    return;
                }
                answer(
                        ctx,
                        redis(
                                ctx,
                                t -> session.redis()
                                        .read(t, name)
                                        .map(ApiJson::keyValue)
                                        .orElseThrow(() -> new DataException(
                                                "there is no key " + name + " (it may have just expired)"))));
            });
            config.routes.post("/api/v1/data/{service}/key/delete", ctx -> {
                if (!(ctx.bodyAsClass(Map.class).get("name") instanceof String name)) {
                    ctx.status(400).json(ApiJson.error("send {\"name\": \"the:key\"}"));
                    return;
                }
                answer(ctx, redis(ctx, t -> Map.of("existed", session.redis().delete(t, name))));
            });
            config.routes.post("/api/v1/data/{service}/key/expire", ctx -> {
                Map<?, ?> body = ctx.bodyAsClass(Map.class);
                if (!(body.get("name") instanceof String name)
                        || (body.get("seconds") != null && !(body.get("seconds") instanceof Number))) {
                    ctx.status(400)
                            .json(ApiJson.error("send {\"name\": \"the:key\", \"seconds\": 60} (null: never expires)"));
                    return;
                }
                Optional<Duration> ttl = body.get("seconds") instanceof Number n
                        ? Optional.of(Duration.ofMillis(Math.max(1, Math.round(n.doubleValue() * 1000))))
                        : Optional.empty();
                answer(ctx, redis(ctx, t -> Map.of("existed", session.redis().expire(t, name, ttl))));
            });
            config.routes.post("/api/v1/data/{service}/key/edit", ctx -> {
                Map<?, ?> body = ctx.bodyAsClass(Map.class);
                Optional<KeyValueBrowser.Edit> edit = redisEdit(body.get("edit"));
                if (!(body.get("name") instanceof String name) || edit.isEmpty()) {
                    ctx.status(400)
                            .json(ApiJson.error("send {\"name\": \"the:key\", \"edit\": {\"op\": \"hash-set\", "
                                    + "\"field\", \"value\"}} (ops: set-text, set-json, hash-set, hash-delete, list-set, "
                                    + "list-push, list-remove, set-add, set-remove, score-set, score-remove)"));
                    return;
                }
                answer(ctx, redis(ctx, t -> {
                    session.redis().edit(t, name, edit.get());
                    return Map.of("edited", true);
                }));
            });
            config.routes.post("/api/v1/data/{service}/command", ctx -> {
                Map<?, ?> body = ctx.bodyAsClass(Map.class);
                if (!(body.get("line") instanceof String line)) {
                    ctx.status(400).json(ApiJson.error("send {\"line\": \"GET user:1\"}"));
                    return;
                }
                boolean confirmed = Boolean.TRUE.equals(body.get("confirmed"));
                answer(ctx, redis(ctx, t -> {
                    List<String> words = RedisCommandLine.split(line);
                    RedisCommandLine.refused(words).ifPresent(why -> {
                        throw new DataException(why);
                    });
                    Optional<String> risk = RedisCommandLine.risk(words);
                    if (risk.isPresent() && !confirmed) {
                        return Map.of("confirm", risk.get()); // the app asks, then sends again with confirmed
                    }
                    return Map.of("reply", session.redis().command(t, words));
                }));
            });
            if (extras) { // extras: the MongoDB pages
                // MongoDB: databases → collections → documents, as JSON text.
                config.routes.get(
                        "/api/v1/data/{service}/mongo/databases",
                        ctx -> answer(
                                ctx,
                                mongo(
                                        ctx,
                                        t -> Map.of(
                                                "databases",
                                                session.documents().databases(t).stream()
                                                        .map(d -> Map.of("name", d.name(), "sizeBytes", d.sizeBytes()))
                                                        .toList()))));
                config.routes.get("/api/v1/data/{service}/mongo/collections", ctx -> {
                    String db = Objects.requireNonNullElse(ctx.queryParam("db"), "");
                    answer(
                            ctx,
                            mongo(
                                    ctx,
                                    t -> Map.of(
                                            "collections",
                                            session.documents().collections(t, db).stream()
                                                    .map(c -> Map.of("name", c.name(), "documents", c.documents()))
                                                    .toList())));
                });
                config.routes.post("/api/v1/data/{service}/mongo/{action}", ctx -> {
                    Map<?, ?> b = ctx.bodyAsClass(Map.class);
                    if (!(b.get("db") instanceof String db) || !(b.get("collection") instanceof String coll)) {
                        ctx.status(400).json(ApiJson.error("send {\"db\", \"collection\", ...}"));
                        return;
                    }
                    String filter = b.get("filter") instanceof String f ? f : "";
                    String sort = b.get("sort") instanceof String s ? s : "";
                    String document = b.get("document") instanceof String d ? d : "";
                    String id = b.get("id") instanceof String i ? i : "";
                    int skip = b.get("skip") instanceof Number n ? Math.max(0, n.intValue()) : 0;
                    int limit = b.get("limit") instanceof Number n ? Math.clamp(n.intValue(), 1, 500) : 50;
                    answer(ctx, mongo(ctx, t -> switch (ctx.pathParam("action")) {
                        case "find" -> {
                            var page = session.documents().find(t, db, coll, filter, sort, skip, limit);
                            yield Map.of(
                                    "documents",
                                    page.documents(),
                                    "matching",
                                    page.matching(),
                                    "ms",
                                    page.took().toMillis());
                        }
                        case "insert" -> Map.of("id", session.documents().insert(t, db, coll, document));
                        case "replace" -> {
                            session.documents().replace(t, db, coll, id, document);
                            yield Map.of("replaced", true);
                        }
                        case "delete" -> Map.of("deleted", session.documents().delete(t, db, coll, id));
                        default -> throw new DataException("unknown action: use find, insert, replace or delete");
                    }));
                });
            }
            config.routes.get("/api/v1/data/{service}/stream", ctx -> {
                String key = Objects.requireNonNullElse(ctx.queryParam("key"), "");
                answer(ctx, redis(ctx, t -> ApiJson.stream(key, session.redis().stream(t, key))));
            });
            config.routes.get(
                    "/api/v1/data/{service}/server",
                    ctx -> answer(
                            ctx, redis(ctx, t -> ApiJson.server(session.redis().server(t)))));
            config.routes.post(
                    "/api/v1/data/{service}/keys/export",
                    ctx -> answer(ctx, data.exportKeys(ctx.pathParam("service"), ctx.bodyAsClass(Map.class))));
            config.routes.post(
                    "/api/v1/data/{service}/keys/import",
                    ctx -> answer(ctx, data.importKeys(ctx.pathParam("service"), ctx.bodyAsClass(Map.class))));
            config.routes.get("/api/v1/data/{service}/memory", ctx -> {
                String pattern = Objects.requireNonNullElse(ctx.queryParam("pattern"), "*");
                answer(ctx, redis(ctx, t -> ApiJson.memory(session.redis().memory(t, pattern, MEMORY_SCAN_MAX))));
            });
            config.routes.get(
                    "/api/v1/data/{service}/slowlog",
                    ctx -> answer(
                            ctx, redis(ctx, t -> ApiJson.slowLog(session.redis().slowLog(t)))));
            config.routes.post(
                    "/api/v1/data/{service}/slowlog/reset",
                    ctx -> answer(ctx, redis(ctx, t -> {
                        session.redis().resetSlowLog(t);
                        return Map.of("reset", true);
                    })));
            // Delete by pattern: first the preview (how many, which), then the delete itself, as two explicit steps.
            config.routes.get("/api/v1/data/{service}/matching", ctx -> {
                String pattern = Objects.requireNonNullElse(ctx.queryParam("pattern"), "");
                if (pattern.isBlank()) {
                    ctx.status(400).json(ApiJson.error("send ?pattern=session:*"));
                    return;
                }
                answer(ctx, redis(ctx, t -> {
                    var m = session.redis().matching(t, pattern, 20);
                    return Map.of("count", m.count(), "sample", m.sample());
                }));
            });
            config.routes.post("/api/v1/data/{service}/keys/delete", ctx -> {
                if (!(ctx.bodyAsClass(Map.class).get("pattern") instanceof String pattern) || pattern.isBlank()) {
                    ctx.status(400).json(ApiJson.error("send {\"pattern\": \"session:*\"}"));
                    return;
                }
                answer(ctx, redis(ctx, t -> Map.of("deleted", session.redis().deleteMatching(t, pattern))));
            });
            config.routes.post("/api/v1/data/{service}/publish", ctx -> {
                Map<?, ?> body = ctx.bodyAsClass(Map.class);
                if (!(body.get("channel") instanceof String channel)
                        || channel.isBlank()
                        || !(body.get("message") instanceof String message)) {
                    ctx.status(400).json(ApiJson.error("send {\"channel\": \"orders\", \"message\": \"...\"}"));
                    return;
                }
                answer(ctx, redis(ctx, t -> Map.of("receivers", session.redis().publish(t, channel, message))));
            });
            // Live streams: start one, then poll it with ?after=<last seq seen>; it closes when nobody polls.
            config.routes.post("/api/v1/data/{service}/watch", ctx -> {
                Map<?, ?> body = ctx.bodyAsClass(Map.class);
                Optional<KeyValueBrowser.WatchKind> kind = watchKind(body);
                if (kind.isEmpty()) {
                    ctx.status(400)
                            .json(
                                    ApiJson.error(
                                            "send {\"kind\": \"monitor\"} or {\"kind\": \"subscribe\", \"patterns\": [\"orders.*\"]}"));
                    return;
                }
                answer(ctx, redis(ctx, t -> watches.start(session.redis(), t, kind.get())));
            });
            config.routes.get("/api/v1/data/{service}/watch/{id}", ctx -> {
                String after = Objects.requireNonNullElse(ctx.queryParam("after"), "0");
                if (!after.matches("\\d{1,18}")) {
                    ctx.status(400).json(ApiJson.error("after must be a number"));
                    return;
                }
                Optional<Map<String, Object>> events =
                        watches.poll(ctx.pathParam("service"), ctx.pathParam("id"), Long.parseLong(after));
                if (events.isEmpty()) {
                    ctx.status(404).json(ApiJson.error("that stream is closed (nobody looked at it for a while)"));
                    return;
                }
                ctx.json(events.get());
            });
            config.routes.post(
                    "/api/v1/data/{service}/watch/{id}/stop",
                    ctx -> ctx.json(Map.of("stopped", watches.stop(ctx.pathParam("service"), ctx.pathParam("id")))));
            // The Workbench: the API client. The engine sends the requests (no CORS limits) and keeps the files.
            // The Workbench: mail the app sent to the project's Mailpit.
            // The Inbox's queues: RabbitMQ queues and Kafka topics, their messages (looked at, never taken) and
            // readers.
            if (extras) { // extras: RabbitMQ, Kafka and SQS queues
                config.routes.get("/api/v1/queues", ctx -> answer(ctx, queues.brokers()));
                config.routes.get(
                        "/api/v1/queues/{service}", ctx -> answer(ctx, queues.queues(ctx.pathParam("service"))));
                config.routes.get(
                        "/api/v1/queues/{service}/{queue}/messages",
                        ctx -> answer(
                                ctx,
                                queues.messages(
                                        ctx.pathParam("service"),
                                        ctx.pathParam("queue"),
                                        parseIntOr(ctx.queryParam("max"), 20))));
                config.routes.get(
                        "/api/v1/queues/{service}/{queue}/readers",
                        ctx -> answer(ctx, queues.readers(ctx.pathParam("service"), ctx.pathParam("queue"))));
                config.routes.post(
                        "/api/v1/queues/{service}/{queue}/publish",
                        ctx -> answer(
                                ctx,
                                queues.publish(
                                        ctx.pathParam("service"), ctx.pathParam("queue"), ctx.bodyAsClass(Map.class))));
            }
            if (extras) { // extras: the mail catcher (Mailpit) inbox
                config.routes.get("/api/v1/mail", ctx -> answer(ctx, mail.catchers()));
                config.routes.get(
                        "/api/v1/mail/{service}/messages",
                        ctx -> answer(
                                ctx,
                                mail.list(
                                        ctx.pathParam("service"),
                                        ctx.queryParam("search"),
                                        limit(ctx.queryParam("limit"), 50))));
                config.routes.get(
                        "/api/v1/mail/{service}/messages/{id}",
                        ctx -> answer(ctx, mail.read(ctx.pathParam("service"), ctx.pathParam("id"))));
                config.routes.post(
                        "/api/v1/mail/{service}/delete",
                        ctx -> answer(ctx, mail.delete(ctx.pathParam("service"), ctx.bodyAsClass(Map.class))));
                config.routes.get("/api/v1/mail/{service}/messages/{id}/parts/{part}", ctx -> {
                    try {
                        var bytes =
                                mail.attachment(ctx.pathParam("service"), ctx.pathParam("id"), ctx.pathParam("part"));
                        if (bytes.isEmpty()) {
                            ctx.status(404).json(ApiJson.error("no such mail catcher in the open project"));
                        } else {
                            ctx.contentType("application/octet-stream").result(bytes.get());
                        }
                    } catch (DataException e) {
                        ctx.status(422).json(ApiJson.error(e.getMessage()));
                    }
                });
                config.routes.post(
                        "/api/v1/mail/{service}/messages/{id}/parts/{part}/save",
                        ctx -> answer(
                                ctx,
                                mail.saveAttachment(
                                        ctx.pathParam("service"),
                                        ctx.pathParam("id"),
                                        ctx.pathParam("part"),
                                        ctx.bodyAsClass(Map.class))));
            }
            config.routes.get("/api/v1/http", ctx -> answer(ctx, requests.overview()));
            config.routes.post("/api/v1/http/send", ctx -> answer(ctx, requests.send(ctx.bodyAsClass(Map.class))));
            config.routes.post("/api/v1/http/run", ctx -> answer(ctx, requests.run(ctx.bodyAsClass(Map.class))));
            config.routes.post("/api/v1/http/share", ctx -> answer(ctx, requests.share(ctx.bodyAsClass(Map.class))));
            config.routes.post("/api/v1/http/cookies/clear", ctx -> answer(ctx, requests.clearCookies()));
            config.routes.post(
                    "/api/v1/http/import/openapi",
                    ctx -> answer(ctx, requests.importOpenApi(ctx.bodyAsClass(Map.class))));
            config.routes.post(
                    "/api/v1/http/import/postman",
                    ctx -> answer(ctx, requests.importPostman(ctx.bodyAsClass(Map.class))));
            config.routes.post(
                    "/api/v1/http/import/curl", ctx -> answer(ctx, HttpWorkbench.fromCurl(ctx.bodyAsClass(Map.class))));
            if (extras) { // extras: the mock server
                // The mock server: fake routes served on a port of this machine.
                config.routes.get("/api/v1/http/mock", ctx -> {
                    String after = Objects.requireNonNullElse(ctx.queryParam("after"), "0");
                    answer(ctx, mocks.overview(after.matches("\\d{1,12}") ? Long.parseLong(after) : 0));
                });
                config.routes.post(
                        "/api/v1/http/mock/routes", ctx -> answer(ctx, mocks.save(ctx.bodyAsClass(Map.class))));
                config.routes.post(
                        "/api/v1/http/mock/routes/{id}/delete", ctx -> answer(ctx, mocks.delete(ctx.pathParam("id"))));
                config.routes.post(
                        "/api/v1/http/mock/start", ctx -> answer(ctx, mocks.start(ctx.bodyAsClass(Map.class))));
                config.routes.post("/api/v1/http/mock/stop", ctx -> {
                    mocks.stop();
                    answer(ctx, mocks.overview(Long.MAX_VALUE));
                });
            }
            // WebSocket connections of the API client: open, then poll ?after=<last seq seen>, send, close.
            config.routes.post(
                    "/api/v1/http/socket",
                    ctx -> answer(ctx, requests.openSocket(ctx.bodyAsClass(Map.class), sockets)));
            config.routes.get("/api/v1/http/socket/{id}", ctx -> {
                String after = Objects.requireNonNullElse(ctx.queryParam("after"), "0");
                var polled = sockets.poll(ctx.pathParam("id"), after.matches("\\d{1,12}") ? Long.parseLong(after) : 0);
                if (polled.isEmpty()) {
                    ctx.status(404).json(ApiJson.error("that connection is closed"));
                } else {
                    ctx.json(polled.get());
                }
            });
            config.routes.post("/api/v1/http/socket/{id}/send", ctx -> {
                if (!(ctx.bodyAsClass(Map.class).get("text") instanceof String text)) {
                    ctx.status(400).json(ApiJson.error("send {\"text\": \"...\"}"));
                    return;
                }
                boolean sent = sockets.send(ctx.pathParam("id"), text);
                ctx.status(sent ? 200 : 409)
                        .json(sent ? Map.of("sent", true) : ApiJson.error("the connection isn't open"));
            });
            config.routes.post(
                    "/api/v1/http/socket/{id}/close",
                    ctx -> ctx.json(Map.of("closed", sockets.close(ctx.pathParam("id")))));
            config.routes.post(
                    "/api/v1/http/graphql/schema",
                    ctx -> answer(ctx, requests.graphQlSchema(ctx.bodyAsClass(Map.class))));
            config.routes.post("/api/v1/http/curl", ctx -> answer(ctx, requests.toCurl(ctx.bodyAsClass(Map.class))));
            config.routes.get("/api/v1/http/sent/{id}", ctx -> {
                if (!ctx.pathParam("id").matches("\\d{1,12}")) {
                    ctx.status(400).json(ApiJson.error("id must be a number"));
                    return;
                }
                answer(ctx, requests.sent(Long.parseLong(ctx.pathParam("id"))));
            });
            config.routes.post("/api/v1/http/saved", ctx -> answer(ctx, requests.save(ctx.bodyAsClass(Map.class))));
            config.routes.post(
                    "/api/v1/http/saved/{id}/delete", ctx -> answer(ctx, requests.delete(ctx.pathParam("id"))));
            config.routes.post(
                    "/api/v1/http/variables", ctx -> answer(ctx, requests.variables(ctx.bodyAsClass(Map.class))));
            config.routes.get("/api/v1/services/{name}", ctx -> answer(ctx, session.details(ctx.pathParam("name"))));
            config.routes.post(
                    "/api/v1/services/{name}/{action}",
                    ctx -> answer(ctx, session.serviceAction(ctx.pathParam("name"), ctx.pathParam("action"))));
            // POST, not GET: revealing a value is a deliberate action, never a link that a page could load.
            config.routes.post(
                    "/api/v1/services/{name}/env/{key}/reveal",
                    ctx -> answer(ctx, session.reveal(ctx.pathParam("name"), ctx.pathParam("key"))));
            config.routes.ws("/api/v1/events", ws -> {
                ws.onConnect(ctx -> {
                    // Browsers can't add headers to a WebSocket, so the token comes as ?token=...
                    if (!tokenMatches(ctx.queryParam("token")) || !originAllowed(ctx.header("Origin"))) {
                        ctx.closeSession(4401, "missing or wrong token, or a page that isn't the DevDesk app");
                        return;
                    }
                    EventClient client = new EventClient(
                            ctx.sessionId(),
                            ctx::send,
                            () -> ctx.closeSession(4408, "too far behind: reconnect to resync"));
                    clients.put(ctx.sessionId(), client);
                    session.connect(client);
                });
                ws.onClose(ctx -> {
                    EventClient c = clients.remove(ctx.sessionId());
                    if (c != null) {
                        session.disconnect(c);
                    }
                });
            });
            // "Shell" on a service: a terminal window. Same token and origin checks as the events socket.
            config.routes.ws(
                    "/api/v1/terminal",
                    ws -> terminals.configure(
                            ws, ctx -> tokenMatches(ctx.queryParam("token")) && originAllowed(ctx.header("Origin"))));
            config.routes.exception(
                    Exception.class,
                    (e, ctx) -> ctx.status(500)
                            .json(ApiJson.error("internal error (a DevDesk bug, please report it): " + e)));
        });
        app.start("127.0.0.1", 0);
        return app.port();
    }

    void stop() {
        watches.closeAll();
        sockets.closeAll();
        terminals.closeAll();
        recorder.stopAll();
        remote.close();
        mocks.stop();
        if (app != null) {
            app.stop();
        }
    }

    /** Runs before every request: CORS headers for the app, then the three checks. */
    private void guard(Context ctx) {
        String origin = ctx.header("Origin");
        if (originAllowed(origin) && origin != null) {
            ctx.header("Access-Control-Allow-Origin", origin);
            ctx.header("Access-Control-Allow-Headers", "Authorization, Content-Type");
            ctx.header("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            ctx.header("Vary", "Origin");
        }
        refusal(ctx.host(), origin, ctx.method().name(), ctx.path(), ctx.header("Authorization"), token)
                .ifPresent(r -> {
                    ctx.status(r.status()).json(ApiJson.error(r.message()));
                    ctx.skipRemainingHandlers();
                });
    }

    /** Why a request is turned away: the HTTP status and a message for the caller. */
    record Refusal(int status, String message) {}

    /** The three checks of {@link #guard}, as a plain function of the request: empty means it may go on. */
    static Optional<Refusal> refusal(
            String host, String origin, String method, String path, String authorization, byte[] token) {
        if (!hostAllowed(host)) {
            return Optional.of(new Refusal(403, "wrong Host header: DevDesk only answers on 127.0.0.1"));
        }
        if (!originAllowed(origin)) {
            return Optional.of(new Refusal(403, "requests from " + origin + " are not allowed"));
        }
        boolean preflight = method.equals("OPTIONS"); // browsers send these without the token
        // WebSockets: checked in onConnect (browsers can't send the header there)
        boolean sockets = path.equals("/api/v1/events") || path.equals("/api/v1/terminal");
        String given = authorization == null ? null : authorization.replaceFirst("^Bearer ", "");
        if (!preflight && !sockets && !tokenMatches(token, given)) {
            return Optional.of(new Refusal(401, "missing or wrong token"));
        }
        return Optional.empty();
    }

    private static int parseIntOr(String text, int fallback) {
        try {
            return text == null ? fallback : Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Constant-time comparison, so the response time doesn't hint at how much of a guess was right. */
    private boolean tokenMatches(String given) {
        return tokenMatches(token, given);
    }

    static boolean tokenMatches(byte[] token, String given) {
        return given != null && MessageDigest.isEqual(token, given.getBytes(StandardCharsets.UTF_8));
    }

    static boolean hostAllowed(String host) {
        if (host == null) {
            return false;
        }
        String name = host.replaceFirst(":\\d+$", "");
        return name.equals("127.0.0.1") || name.equals("localhost");
    }

    /** No Origin at all = not a browser page (e.g. curl or the Tauri Rust side): allowed, the token still applies. */
    static boolean originAllowed(String origin) {
        return origin == null || ALLOWED_ORIGINS.contains(origin);
    }

    /** At most this many rows per page or query result: enough to read, small enough to stay fast. */
    static final int MAX_ROWS = 2000;

    /** Validates a rows request from the app (API input is a boundary: CLAUDE.md §5). */
    static Optional<SqlBrowser.RowQuery> rowQuery(Map<?, ?> b) {
        if (!(b.get("schema") instanceof String schema) || !(b.get("table") instanceof String table)) {
            return Optional.empty();
        }
        List<SqlBrowser.Filter> filters = new ArrayList<>();
        if (b.get("filters") instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> f)
                        || !(f.get("column") instanceof String column)
                        || !(f.get("op") instanceof String op)) {
                    return Optional.empty();
                }
                SqlBrowser.Op parsed;
                try {
                    parsed = SqlBrowser.Op.valueOf(op.toUpperCase().replace('-', '_'));
                } catch (IllegalArgumentException e) {
                    return Optional.empty();
                }
                filters.add(new SqlBrowser.Filter(column, parsed, String.valueOf(f.get("value"))));
            }
        }
        Optional<String> orderBy =
                b.get("orderBy") instanceof String o && !o.isBlank() ? Optional.of(o) : Optional.empty();
        long offset = b.get("offset") instanceof Number n ? Math.max(0, n.longValue()) : 0;
        int limit = b.get("limit") instanceof Number n ? Math.clamp(n.intValue(), 1, MAX_ROWS) : 100;
        return Optional.of(new SqlBrowser.RowQuery(
                schema, table, orderBy, Boolean.TRUE.equals(b.get("descending")), filters, offset, limit));
    }

    /** Keys measured at most by the memory view: enough to find the big ones, quick on a local Redis. */
    static final int MEMORY_SCAN_MAX = 100_000;

    static Optional<KeyValueBrowser.WatchKind> watchKind(Map<?, ?> b) {
        if ("monitor".equals(b.get("kind"))) {
            return Optional.of(new KeyValueBrowser.WatchKind.Monitor());
        }
        if ("subscribe".equals(b.get("kind")) && b.get("patterns") instanceof List<?> list && !list.isEmpty()) {
            List<String> patterns = new ArrayList<>();
            for (Object o : list) {
                if (!(o instanceof String p) || p.isBlank()) {
                    return Optional.empty();
                }
                patterns.add(p.strip());
            }
            return Optional.of(new KeyValueBrowser.WatchKind.Subscribe(patterns));
        }
        return Optional.empty();
    }

    /** Validates one Redis value edit from the app. */
    static Optional<KeyValueBrowser.Edit> redisEdit(Object o) {
        if (!(o instanceof Map<?, ?> e) || !(e.get("op") instanceof String op)) {
            return Optional.empty();
        }
        String value = e.get("value") instanceof String v ? v : null;
        String field = e.get("field") instanceof String f ? f : null;
        String member = e.get("member") instanceof String m ? m : null;
        Long index = e.get("index") instanceof Number n ? n.longValue() : null;
        Double score = e.get("score") instanceof Number n ? n.doubleValue() : null;
        KeyValueBrowser.Edit edit = switch (op) {
            case "set-text" -> value == null ? null : new KeyValueBrowser.Edit.SetText(value);
            case "set-json" -> value == null ? null : new KeyValueBrowser.Edit.SetJson(value);
            case "hash-set" -> field == null || value == null ? null : new KeyValueBrowser.Edit.HashSet(field, value);
            case "hash-delete" -> field == null ? null : new KeyValueBrowser.Edit.HashDelete(field);
            case "list-set" -> index == null || value == null ? null : new KeyValueBrowser.Edit.ListSet(index, value);
            case "list-push" ->
                value == null ? null : new KeyValueBrowser.Edit.ListPush(value, Boolean.TRUE.equals(e.get("head")));
            case "list-remove" -> index == null ? null : new KeyValueBrowser.Edit.ListRemove(index);
            case "set-add" -> member == null ? null : new KeyValueBrowser.Edit.SetAdd(member);
            case "set-remove" -> member == null ? null : new KeyValueBrowser.Edit.SetRemove(member);
            case "score-set" ->
                member == null || score == null || !Double.isFinite(score)
                        ? null
                        : new KeyValueBrowser.Edit.ScoreSet(member, score);
            case "score-remove" -> member == null ? null : new KeyValueBrowser.Edit.ScoreRemove(member);
            case "stream-add" ->
                streamFields(e.get("fields"))
                        .map(KeyValueBrowser.Edit.StreamAdd::new)
                        .orElse(null);
            case "group-create" ->
                e.get("group") instanceof String g && !g.isBlank()
                        ? new KeyValueBrowser.Edit.GroupCreate(g, Boolean.TRUE.equals(e.get("fromStart")))
                        : null;
            case "group-delete" ->
                e.get("group") instanceof String g && !g.isBlank() ? new KeyValueBrowser.Edit.GroupDelete(g) : null;
            default -> null;
        };
        return Optional.ofNullable(edit);
    }

    /** Validates a save from the grid. Values are text or null (SQL NULL); key values are always text. */
    static Optional<SqlEditor.TableChanges> tableChanges(Map<?, ?> b) {
        if (!(b.get("schema") instanceof String schema)
                || !(b.get("table") instanceof String table)
                || !(b.get("changes") instanceof List<?> list)) {
            return Optional.empty();
        }
        List<SqlEditor.RowChange> changes = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> c) || !(c.get("op") instanceof String op)) {
                return Optional.empty();
            }
            Optional<Map<String, Optional<String>>> values = values(c.get("values"));
            Optional<Map<String, String>> key = key(c.get("key"));
            switch (op) {
                case "insert" -> {
                    if (values.isEmpty()) {
                        return Optional.empty();
                    }
                    changes.add(new SqlEditor.RowChange.Insert(values.get()));
                }
                case "update" -> {
                    if (values.isEmpty() || key.isEmpty()) {
                        return Optional.empty();
                    }
                    changes.add(new SqlEditor.RowChange.Update(key.get(), values.get()));
                }
                case "delete" -> {
                    if (key.isEmpty()) {
                        return Optional.empty();
                    }
                    changes.add(new SqlEditor.RowChange.Delete(key.get()));
                }
                default -> {
                    return Optional.empty();
                }
            }
        }
        return Optional.of(new SqlEditor.TableChanges(schema, table, changes));
    }

    /** {"name": "Ada", "note": null}: null is SQL NULL. Numbers and booleans are taken as their text. */
    private static Optional<Map<String, Optional<String>>> values(Object o) {
        if (!(o instanceof Map<?, ?> m)) {
            return Optional.empty();
        }
        Map<String, Optional<String>> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (!(e.getKey() instanceof String name) || e.getValue() instanceof Map || e.getValue() instanceof List) {
                return Optional.empty();
            }
            out.put(name, Optional.ofNullable(e.getValue()).map(String::valueOf));
        }
        return Optional.of(out);
    }

    /** {"id": "7"}: a key column can't be NULL (Postgres forbids NULL in a primary key). */
    private static Optional<Map<String, String>> key(Object o) {
        return values(o)
                .filter(m -> !m.isEmpty() && m.values().stream().allMatch(Optional::isPresent))
                .map(m -> {
                    Map<String, String> out = new LinkedHashMap<>();
                    m.forEach((k, v) -> out.put(k, v.orElseThrow()));
                    return out;
                });
    }

    private EngineSession.Answer redis(Context ctx, java.util.function.Function<DatabaseTarget, Object> call) {
        return session.data(ctx.pathParam("service"), false, call);
    }

    private EngineSession.Answer mongo(Context ctx, java.util.function.Function<DatabaseTarget, Object> call) {
        return session.data(ctx.pathParam("service"), java.util.EnumSet.of(DatabaseTarget.Kind.MONGO), "MongoDB", call);
    }

    private EngineSession.Answer sql(Context ctx, java.util.function.Function<DatabaseTarget, Object> call) {
        return session.data(ctx.pathParam("service"), true, call);
    }

    /**
     * Every table of a schema (blank: every schema) with its columns and links, arranged for drawing. One structure
     * read per table: fine for the up to 150 tables a diagram can show.
     */
    static Map<String, Object> diagram(SqlBrowser browser, DatabaseTarget t, String schema) {
        List<SqlBrowser.Table> tables = browser.tables(t).stream()
                .filter(x -> !x.view() && (schema.isBlank() || x.schema().equals(schema)))
                .toList();
        if (tables.size() > SchemaDiagram.MAX_TABLES) {
            throw new DataException(tables.size() + " tables are too many for one picture (at most "
                    + SchemaDiagram.MAX_TABLES + "): pick one schema");
        }
        Map<SchemaDiagram.TableName, SqlBrowser.Structure> structures = new LinkedHashMap<>();
        Map<SchemaDiagram.TableName, Long> rows = new LinkedHashMap<>();
        for (SqlBrowser.Table x : tables) {
            SchemaDiagram.TableName name = new SchemaDiagram.TableName(x.schema(), x.name());
            browser.structure(t, x.schema(), x.name()).ifPresent(s -> structures.put(name, s));
            rows.put(name, x.estimatedRows());
        }
        return ApiJson.diagram(SchemaDiagram.of(structures), structures, rows);
    }

    /** [{"name": "event", "value": "paid"}, ...]: at least one field, as XADD needs. */
    private static Optional<List<KeyValueBrowser.Field>> streamFields(Object o) {
        if (!(o instanceof List<?> list) || list.isEmpty()) {
            return Optional.empty();
        }
        List<KeyValueBrowser.Field> fields = new ArrayList<>();
        for (Object f : list) {
            if (!(f instanceof Map<?, ?> m)
                    || !(m.get("name") instanceof String n)
                    || n.isBlank()
                    || !(m.get("value") instanceof String v)) {
                return Optional.empty();
            }
            fields.add(new KeyValueBrowser.Field(n, v));
        }
        return Optional.of(fields);
    }

    /** A "?limit=" number, or the default when it's missing or not a number. */
    private static int limit(String given, int fallback) {
        return given != null && given.matches("\\d{1,6}") ? Integer.parseInt(given) : fallback;
    }

    private static void answer(Context ctx, EngineSession.Answer answer) {
        ctx.status(answer.status()).json(answer.body());
    }

    /** Lets Javalin read and write JSON with Jackson 3 (Javalin's built-in mapper uses Jackson 2). */
    private static final class Jackson3Mapper implements JsonMapper {
        @Override
        public String toJsonString(Object obj, Type type) {
            return ApiJson.MAPPER.writeValueAsString(obj);
        }

        @Override
        public <T> T fromJsonString(String json, Type targetType) {
            return ApiJson.MAPPER.readValue(json, ApiJson.MAPPER.constructType(targetType));
        }
    }
}
