package io.github.louch.devdesk.daemon;

import io.github.louch.devdesk.core.data.DatabaseTarget;
import io.github.louch.devdesk.core.diagnosis.DiagnosisEngine;
import io.github.louch.devdesk.core.http.RecentRequests;
import io.github.louch.devdesk.core.project.ProjectSpec;
import io.github.louch.devdesk.core.queue.Broker;
import io.github.louch.devdesk.core.testing.TestCommands;
import io.github.louch.devdesk.data.ElasticSearch;
import io.github.louch.devdesk.data.JdkHttpSender;
import io.github.louch.devdesk.data.JdkLoadGenerator;
import io.github.louch.devdesk.data.JdkMockServer;
import io.github.louch.devdesk.data.JdkRecordingProxy;
import io.github.louch.devdesk.data.JdkWebSockets;
import io.github.louch.devdesk.data.JsonMockBook;
import io.github.louch.devdesk.data.JsonSavedQueries;
import io.github.louch.devdesk.data.KafkaTopics;
import io.github.louch.devdesk.data.MailpitBox;
import io.github.louch.devdesk.data.MongoBrowser;
import io.github.louch.devdesk.data.MysqlBrowser;
import io.github.louch.devdesk.data.MysqlEditor;
import io.github.louch.devdesk.data.PostgresBrowser;
import io.github.louch.devdesk.data.PostgresEditor;
import io.github.louch.devdesk.data.RabbitAdmin;
import io.github.louch.devdesk.data.RedisBrowser;
import io.github.louch.devdesk.data.S3Objects;
import io.github.louch.devdesk.data.SharedRequestBook;
import io.github.louch.devdesk.data.SqliteBackups;
import io.github.louch.devdesk.data.SqliteBrowser;
import io.github.louch.devdesk.data.SqliteEditor;
import io.github.louch.devdesk.data.SqliteFiles;
import io.github.louch.devdesk.data.SqsQueues;
import io.github.louch.devdesk.data.StateFolder;
import io.github.louch.devdesk.docker.DockerConnection;
import io.github.louch.devdesk.linux.changes.KnownGoodFiles;
import io.github.louch.devdesk.linux.diagnosis.LinuxIntrospection;
import io.github.louch.devdesk.linux.doctor.ToolVersions;
import io.github.louch.devdesk.linux.health.LocalHealthProbe;
import io.github.louch.devdesk.linux.network.ProcNetListeners;
import io.github.louch.devdesk.linux.process.LinuxProcessRuntime;
import io.github.louch.devdesk.linux.process.ScopeLeftovers;
import io.github.louch.devdesk.linux.resources.ProcResources;
import io.github.louch.devdesk.linux.sleep.SocketWake;
import io.github.louch.devdesk.linux.terminal.PtyTerminals;
import io.github.louch.devdesk.linux.trust.TrustStore;
import io.github.louch.devdesk.linux.watch.PollingFileTree;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Entry point of {@code devdeskd}, the engine the desktop app starts. Wires every dependency by hand.
 *
 * <p>It prints one line, {@code {"port":...,"token":...}}, so the app knows where to connect. It stops every service
 * and exits when its stdin closes, which happens when the app exits or crashes (ADR 0005: close DevDesk = stop all).
 */
public final class Main {

    private Main() {}

    public static void main(String[] args) throws Exception {
        var env = System.getenv();
        var clock = Clock.systemUTC();
        var random = new SecureRandom();
        var listeners = new ProcNetListeners();
        var engine = new EngineSession.Engine(
                LinuxProcessRuntime.detect(clock, random),
                () -> DockerConnection.connect(env),
                new LocalHealthProbe(new LinuxProcessRuntime(false, clock, random)),
                new LinuxIntrospection(listeners),
                DiagnosisEngine.standard(),
                new ScopeLeftovers(),
                new TrustStore(TrustStore.defaultFile(env)),
                new ProcResources(),
                Map.of(
                        DatabaseTarget.Kind.POSTGRES, new PostgresBrowser(),
                        DatabaseTarget.Kind.MYSQL, new MysqlBrowser(),
                        DatabaseTarget.Kind.SQLITE, new SqliteBrowser()),
                Map.of(
                        DatabaseTarget.Kind.POSTGRES, new PostgresEditor(),
                        DatabaseTarget.Kind.MYSQL, new MysqlEditor(),
                        DatabaseTarget.Kind.SQLITE, new SqliteEditor()),
                new SqliteBackups(),
                SqliteFiles::find,
                new RedisBrowser(),
                new MongoBrowser(),
                new S3Objects(clock),
                new ElasticSearch(),
                env,
                clock,
                random);
        var recentRequests = new RecentRequests();
        // Read once: extras' routes are registered at start, so a change of the switch applies after a restart.
        boolean extras = Extras.enabled(env);
        var session = new EngineSession(
                engine, recentRequests, new ActiveProfiles(StateFolder.of(env).resolve("profiles.properties")), extras);

        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String token = HexFormat.of().formatHex(secret);
        var requests = new HttpWorkbench(
                session, new JdkHttpSender(), p -> SharedRequestBook.forProject(env, p), recentRequests, clock, random);
        var data = new DataWorkbench(
                session,
                StateFolder.of(env).resolve("backups"),
                p -> JsonSavedQueries.forProject(env, p),
                clock,
                random);
        var mail = new MailWorkbench(session, new MailpitBox());
        var sockets = new SocketSessions(new JdkWebSockets(), clock, random);
        var mocks = new MockWorkbench(session, new JdkMockServer(clock), p -> JsonMockBook.forProject(env, p), random);
        var watch = new FileWatchLoop(session, new PollingFileTree(), clock);
        watch.start();
        if (extras) {
            new AutoSleep(session, new SocketWake(), clock).start(); // an extra: off unless turned on
        }
        var changes = new ChangeTracker(
                session,
                session::docker,
                new KnownGoodFiles(StateFolder.of(env).resolve("known-good"), new ToolVersions(env), clock),
                new LocalHealthProbe(new LinuxProcessRuntime(false, clock, random)),
                env,
                clock,
                random);
        changes.start();
        var setup = new SetupWorkbench(session, () -> DockerConnection.connect(env), env, random);
        var server = new ApiServer(
                session,
                requests,
                data,
                new RedisWatches(clock, random),
                mail,
                sockets,
                mocks,
                setup,
                new EnvWorkbench(session, env),
                new TaskRunner(session, env, StateFolder.of(env).resolve("tasks"), clock, ProjectSpec::tasks, "task"),
                new TaskRunner(
                        session,
                        env,
                        StateFolder.of(env).resolve("tests"),
                        clock,
                        p -> TestCommands.of(p, Main::readIfThere),
                        "test"),
                new SnapshotWorkbench(session, StateFolder.of(env).resolve("snapshots"), clock),
                new MigrationCheck(session),
                new QueueWorkbench(
                        session,
                        Map.of(
                                Broker.Kind.RABBITMQ,
                                new RabbitAdmin(),
                                Broker.Kind.KAFKA,
                                new KafkaTopics(),
                                Broker.Kind.SQS,
                                new SqsQueues(clock))),
                changes,
                new CrashBundle(session, changes, StateFolder.of(env).resolve("bundles"), clock),
                new TrafficRecorder(
                        session,
                        new JdkRecordingProxy(clock),
                        StateFolder.of(env).resolve("recordings"),
                        clock),
                new StressLab(session, new JdkLoadGenerator()),
                new RemoteWorkbench(session, StateFolder.of(env).resolve("remotes")),
                watch,
                new TerminalSockets(new ServiceShells(session, new PtyTerminals(), env)),
                extras,
                env,
                token);
        int port = server.start();

        // Ctrl+C, SIGTERM, or the stdin lifeline below: stop every service (dependents first), then the server.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            session.close();
            server.stop();
        }));

        String connection = "{\"port\":" + port + ",\"token\":\"" + token + "\",\"pid\":"
                + ProcessHandle.current().pid() + "}";
        System.out.println(connection);
        System.out.flush();

        List<String> options = List.of(args);
        if (options.contains("--serve")) {
            serve(options, session, connection);
            return; // a server keeps running without the app: no stdin lifeline
        }
        if (!options.contains("--no-stdin-lifeline")) {
            watchStdin();
        }
    }

    /**
     * Server mode, for a deploy or a remote dev box ({@code devdeskd --serve ~/apps/shop [--trust]}): opens the project,
     * trusts it when told (the user saw the commands on their laptop and clicked Deploy), starts it, and writes where
     * the engine listens to ~/.devdesk/server.json. Only 127.0.0.1 listens: the laptop reaches it through SSH.
     */
    private static void serve(List<String> options, EngineSession session, String connection) throws IOException {
        int at = options.indexOf("--serve");
        if (at + 1 >= options.size()) {
            System.err.println("devdeskd --serve needs the project folder: devdeskd --serve ~/apps/shop");
            System.exit(2);
        }
        Path folder = Path.of(options.get(at + 1).replaceFirst("^~", System.getProperty("user.home")));
        Path info = Path.of(System.getProperty("user.home"), ".devdesk", "server.json");
        Files.createDirectories(info.getParent());
        Files.writeString(info, connection);
        EngineSession.Answer opened = session.open(folder);
        if (opened.status() >= 300) {
            System.err.println("devdeskd: couldn't open " + folder + ": " + ApiJson.write(opened.body()));
            return; // keeps running: the laptop's app shows the problem when it connects
        }
        if (options.contains("--trust")) {
            session.trust();
        }
        EngineSession.Answer up = session.up();
        if (up.status() >= 300) {
            System.err.println("devdeskd: couldn't start the project: " + ApiJson.write(up.body()));
        }
    }

    /** For test detection: a file's text, or empty when it isn't there or can't be read. */
    private static Optional<String> readIfThere(Path file) {
        try {
            return Files.isRegularFile(file) ? Optional.of(Files.readString(file)) : Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty(); // unreadable or not text: treated as absent
        }
    }

    /**
     * The app keeps our stdin open. When the app exits, even by crashing, the kernel closes that pipe and read()
     * returns -1 (end of input). That's the signal to stop everything, so no service outlives the app.
     */
    private static void watchStdin() {
        Thread.ofPlatform().name("stdin-lifeline").start(() -> {
            try {
                while (System.in.read() != -1) {
                    // ignore anything the app writes; only the end of input matters
                }
            } catch (IOException e) {
                // a broken pipe means the same: the app is gone
            }
            System.exit(0); // runs the shutdown hook above
        });
    }
}
