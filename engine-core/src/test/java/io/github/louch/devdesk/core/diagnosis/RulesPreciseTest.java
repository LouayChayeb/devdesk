package io.github.louch.devdesk.core.diagnosis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.louch.devdesk.core.config.ConfigParser;
import io.github.louch.devdesk.core.health.ProbeResult;
import io.github.louch.devdesk.core.network.Listener;
import io.github.louch.devdesk.core.process.ExitStatus;
import io.github.louch.devdesk.core.process.OutputLine;
import io.github.louch.devdesk.core.process.StreamKind;
import io.github.louch.devdesk.core.project.ProjectSpec;
import io.github.louch.devdesk.core.state.ServiceState;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Each rule's exact wording, evidence and fix, and the edges of when it fires (one rule at a time). */
class RulesPreciseTest {

    private static final Instant NOW = Instant.parse("2026-10-04T15:00:00Z");
    private static final Path ROOT = Path.of("/work/shop");

    private final FakeIntrospection machine = new FakeIntrospection();
    private final Map<String, ServiceState> states = new HashMap<>();
    private final Map<String, ServiceHistory> history = new HashMap<>();
    private Map<String, String> shellEnv = Map.of("PATH", "/usr/bin:/bin");

    private DiagnosisContext ctx(String yaml) throws Exception {
        ProjectSpec p = ConfigParser.parse(yaml, ROOT, Map.of(), f -> Optional.empty());
        return new DiagnosisContext(p, states, history, shellEnv, machine, NOW);
    }

    private static ServiceHistory history(
            List<OutputLine> output,
            List<ExitStatus> exits,
            List<ProbeResult> probes,
            boolean everHealthy,
            Set<Long> pids) {
        return new ServiceHistory(output, exits, probes, List.of(), everHealthy, pids, false);
    }

    private static OutputLine err(String text) {
        return new OutputLine(StreamKind.STDERR, text);
    }

    private static OutputLine out(String text) {
        return new OutputLine(StreamKind.STDOUT, text);
    }

    private static final String API = "version: 1\nservices:\n  api: { command: node server.js, ports: [3000] }\n";

    // ---- ids: every rule names itself (the verdict and history match on them) ----

    @Test
    void everyRuleHasItsStableId() {
        assertThat(DiagnosisEngine.standard()).isNotNull();
        assertThat(List.of(
                        new PortConflictRule().id(),
                        new ProgramMissingRule().id(),
                        new KilledFromOutsideRule().id(),
                        new CrashRule().id(),
                        new HealthRule().id(),
                        new MissingEnvRule().id(),
                        new MemoryGrowthRule().id(),
                        new SocketGrowthRule().id(),
                        new BlockedRule().id()))
                .containsExactly(
                        "port-conflict",
                        "program-missing",
                        "killed-from-outside",
                        "crash",
                        "health",
                        "missing-env",
                        "memory-growth",
                        "socket-growth",
                        "blocked");
    }

    // ---- health ----

    @Test
    void healthSaysWhereTheServiceReallyListensAndCountsRepeatedResults() throws Exception {
        String yaml = "version: 1\nservices:\n  api:\n    command: node s.js\n"
                + "    health: { type: http, url: \"http://127.0.0.1:3001/health\" }\n";
        states.put("api", new ServiceState.Unhealthy(10, 0, "no answer"));
        var refused = new ProbeResult.Failed("connection refused");
        history.put("api", history(List.of(), List.of(), List.of(refused, refused, refused), false, Set.of(10L, 11L)));
        machine.listeners.put(3000, new Listener(3000, "127.0.0.1", 1000, Optional.of(11L), Optional.empty()));
        machine.listeners.put(9229, new Listener(9229, "127.0.0.1", 1000, Optional.of(11L), Optional.empty()));
        machine.listeners.put(5432, new Listener(5432, "127.0.0.1", 1000, Optional.of(99L), Optional.empty()));

        var f = new HealthRule().evaluate(ctx(yaml), "api");

        assertThat(f).singleElement().satisfies(x -> {
            assertThat(x.title()).isEqualTo("api never became healthy");
            assertThat(x.explanation())
                    .startsWith("api started, but its health check never passed: no answer.")
                    .contains("It listens on [3000, 9229], but the check waits on port 3001.");
            assertThat(x.evidence())
                    .containsExactly(
                            "check failed: connection refused  (3 times)",
                            "api (PIDs [10, 11]) listens on ports [3000, 9229]");
            assertThat(x.actions())
                    .containsExactly(
                            "make the health check use port 3000, or make api listen on 3001",
                            "open the health URL or port yourself to see what it answers");
        });
    }

    @Test
    void healthAfterBeingHealthyAndWhenNothingListensYet() throws Exception {
        String yaml = "version: 1\nservices:\n  api:\n    command: node s.js\n    health: { type: tcp, port: 3000 }\n";
        states.put("api", new ServiceState.Unhealthy(10, 0, "refused"));
        history.put(
                "api",
                history(
                        List.of(),
                        List.of(),
                        List.of(new ProbeResult.Passed("ok"), new ProbeResult.Failed("refused")),
                        true,
                        Set.of(10L)));

        var f = new HealthRule().evaluate(ctx(yaml), "api").getFirst();

        assertThat(f.title()).isEqualTo("api became unhealthy");
        assertThat(f.explanation())
                .startsWith("api was healthy before, and its check now fails: refused.")
                .contains("It isn't listening on any port yet");
        assertThat(f.evidence())
                .containsExactly("check passed: ok", "check failed: refused", "api (PIDs [10]) listens on no TCP port");
        assertThat(f.actions()).first().asString().contains("start_period");
    }

    @Test
    void healthWithNoProcessAndAnHttpUrlWithoutPortMeansPort80() throws Exception {
        String yaml =
                "version: 1\nservices:\n  web:\n    command: x\n    health: { type: http, url: \"http://localhost/\" }\n";
        states.put("web", new ServiceState.Unhealthy(0, 0, "down"));
        history.put("web", history(List.of(), List.of(), List.of(), false, Set.of()));
        machine.listeners.put(8080, new Listener(8080, "127.0.0.1", 1000, Optional.of(5L), Optional.empty()));

        var f = new HealthRule().evaluate(ctx(yaml), "web").getFirst();

        assertThat(f.evidence()).contains("no running processes found for web");
        assertThat(f.explanation()).doesNotContain("listens on").doesNotContain("isn't listening");
        assertThat(f.actions()).containsExactly("open the health URL or port yourself to see what it answers");
    }

    @Test
    void healthStaysQuietUnlessUnhealthy() throws Exception {
        states.put("api", new ServiceState.Running(10, 0));
        assertThat(new HealthRule().evaluate(ctx(API), "api")).isEmpty();
    }

    // ---- an app crashed under nodemon, which keeps running ----

    @Test
    void nodemonKeepsRunningButTheAppCrashedAndTheLinesBeforeSayWhy() throws Exception {
        states.put("api", new ServiceState.Running(10, 0));
        history.put(
                "api",
                history(
                        List.of(
                                out("[nodemon] starting `node src/index.js`"),
                                err("Config validation error: \"JWT_SECRET\" is not allowed to be empty"),
                                out("[nodemon] app crashed - waiting for file changes before starting...")),
                        List.of(),
                        List.of(),
                        false,
                        Set.of(10L)));

        var f = new WrapperCrashRule().evaluate(ctx(API), "api");

        assertThat(f).singleElement().satisfies(x -> {
            assertThat(x.title()).isEqualTo("api's app crashed (nodemon keeps running)");
            assertThat(x.evidence())
                    .contains("stderr | Config validation error: \"JWT_SECRET\" is not allowed to be empty");
        });
    }

    @Test
    void nodemonWithATakenPortNamesThePortAndWhoHasIt() throws Exception {
        states.put("api", new ServiceState.Running(10, 0));
        history.put(
                "api",
                history(
                        List.of(
                                err("Error: listen EADDRINUSE: address already in use :::3000"),
                                out("[nodemon] app crashed - waiting for file changes before starting...")),
                        List.of(),
                        List.of(),
                        false,
                        Set.of(10L)));
        machine.listeners.put(3000, new Listener(3000, "0.0.0.0", 1000, Optional.of(4121L), Optional.empty()));

        var f = new WrapperCrashRule().evaluate(ctx(API), "api").getFirst();

        assertThat(f.title()).isEqualTo("Port 3000 is already in use");
        assertThat(f.evidence()).anyMatch(e -> e.contains("PID 4121"));
    }

    @Test
    void nodemonThatRestartedTheAppAfterTheCrashIsFine() throws Exception {
        states.put("api", new ServiceState.Running(10, 0));
        history.put(
                "api",
                history(
                        List.of(
                                out("[nodemon] app crashed - waiting for file changes before starting..."),
                                out("[nodemon] restarting due to changes..."),
                                out("[nodemon] starting `node src/index.js`")),
                        List.of(),
                        List.of(),
                        false,
                        Set.of(10L)));

        assertThat(new WrapperCrashRule().evaluate(ctx(API), "api")).isEmpty();
    }

    // ---- crash ----

    @Test
    void aCrashShowsTheLastTenErrorLinesOnceEach() throws Exception {
        states.put("api", new ServiceState.Crashed(ExitStatus.of(1), 0));
        List<OutputLine> lines = new ArrayList<>(List.of(out("starting"), out("listening")));
        for (int i = 1; i <= 12; i++) {
            lines.add(err("error " + i));
        }
        lines.add(err("error 12")); // printed twice: shown once
        history.put("api", history(lines, List.of(ExitStatus.of(1)), List.of(), false, Set.of()));

        var f = new CrashRule().evaluate(ctx(API), "api").getFirst();

        assertThat(f.title()).isEqualTo("api crashed");
        assertThat(f.evidence()).hasSize(9).first().isEqualTo("stderr | error 4");
        assertThat(f.evidence()).last().isEqualTo("stderr | error 12");
        assertThat(f.evidence()).noneMatch(e -> e.contains("listening"));
    }

    @Test
    void withoutStderrTheStdoutLinesAreShownAndALoopShowsItsExits() throws Exception {
        states.put("api", new ServiceState.CrashLoop(ExitStatus.of(2), 5));
        history.put(
                "api",
                history(
                        List.of(out("bad config")),
                        List.of(ExitStatus.of(2), ExitStatus.of(2)),
                        List.of(),
                        false,
                        Set.of()));

        var f = new CrashRule().evaluate(ctx(API), "api").getFirst();

        assertThat(f.title()).isEqualTo("api keeps crashing");
        assertThat(f.explanation())
                .startsWith("api crashed 2 times in a short time, so DevDesk stopped restarting it.");
        assertThat(f.evidence()).containsExactly("last exits: [2, 2]", "stdout | bad config");
    }

    @Test
    void aSilentCrashSaysSoAndOtherRulesTakeTheSpecificCases() throws Exception {
        states.put("api", new ServiceState.Crashed(ExitStatus.of(1), 0));
        history.put("api", history(List.of(), List.of(), List.of(), false, Set.of()));
        assertThat(new CrashRule().evaluate(ctx(API), "api").getFirst().evidence())
                .containsExactly("it printed nothing before exiting");

        for (ExitStatus specific : List.of(ExitStatus.of(127), ExitStatus.of(126), ExitStatus.of(137))) {
            history.put("api", history(List.of(), List.of(specific), List.of(), false, Set.of()));
            assertThat(new CrashRule().evaluate(ctx(API), "api"))
                    .as(specific.toString())
                    .isEmpty();
        }
        states.put("api", new ServiceState.Running(1, 0));
        assertThat(new CrashRule().evaluate(ctx(API), "api")).isEmpty();
    }

    // ---- program missing ----

    @Test
    void aMissingProgramBeforeStartOrAfterExit127AndTheServicesOwnPath() throws Exception {
        String yaml = "version: 1\nservices:\n  api:\n    command: FOO=1 node server.js\n"
                + "    environment: { PATH: \"/opt/node/bin:/opt/node/bin:/usr/bin\" }\n";
        states.put("api", new ServiceState.Stopped());

        var before = new ProgramMissingRule().evaluate(ctx(yaml), "api").getFirst();
        assertThat(before.title()).isEqualTo("Program `node` is not installed or not on PATH");
        assertThat(before.evidence()).containsExactly("searched PATH: /opt/node/bin:/usr/bin");

        // Running fine: nothing to say, even though node isn't "found" here.
        states.put("api", new ServiceState.Running(1, 0));
        history.put("api", history(List.of(), List.of(), List.of(), false, Set.of()));
        assertThat(new ProgramMissingRule().evaluate(ctx(yaml), "api")).isEmpty();

        states.put("api", new ServiceState.Crashed(ExitStatus.of(126), 0));
        history.put("api", history(List.of(), List.of(ExitStatus.of(126)), List.of(), false, Set.of()));
        assertThat(new ProgramMissingRule().evaluate(ctx(yaml), "api")).hasSize(1);

        machine.programs.put("node", Path.of("/opt/node/bin/node"));
        assertThat(new ProgramMissingRule().evaluate(ctx(yaml), "api"))
                .as("found: a 127 from inside")
                .isEmpty();
    }

    @Test
    void aPathLikeCommandIsCheckedAsAFile() throws Exception {
        String yaml = "version: 1\nservices:\n  job: { command: ./run.sh --now, directory: jobs }\n";
        states.put("job", new ServiceState.Stopped());

        var missing = new ProgramMissingRule().evaluate(ctx(yaml), "job").getFirst();
        assertThat(missing.title()).isEqualTo("`./run.sh` does not exist");
        assertThat(missing.evidence()).containsExactly("checked: " + ROOT.resolve("jobs/run.sh"));

        machine.fileStatuses.put(ROOT.resolve("jobs/run.sh"), Introspection.FileStatus.NOT_EXECUTABLE);
        var notExec = new ProgramMissingRule().evaluate(ctx(yaml), "job").getFirst();
        assertThat(notExec.actions()).containsExactly("chmod +x " + ROOT.resolve("jobs/run.sh"));

        machine.fileStatuses.put(ROOT.resolve("jobs/run.sh"), Introspection.FileStatus.EXECUTABLE);
        assertThat(new ProgramMissingRule().evaluate(ctx(yaml), "job")).isEmpty();
    }

    @Test
    void theFirstProgramOfACommandLine() {
        assertThat(ProgramMissingRule.firstProgram("A=1 B=2 npm run dev")).contains("npm");
        assertThat(ProgramMissingRule.firstProgram("python3 a.py && b")).contains("python3");
        assertThat(ProgramMissingRule.firstProgram("npm; x")).contains("npm");
        assertThat(ProgramMissingRule.firstProgram("cd api && npm start")).isEmpty();
        assertThat(ProgramMissingRule.firstProgram("$HOME/bin/x")).isEmpty();
        assertThat(ProgramMissingRule.firstProgram("`which x`")).isEmpty();
        assertThat(ProgramMissingRule.firstProgram("   ")).isEmpty();
        assertThat(ProgramMissingRule.firstProgram("=x y")).contains("=x");
    }

    @Test
    void longPathsAreShortenedInTheEvidence() throws Exception {
        shellEnv = Map.of("PATH", "/a:/b:/c:/d:/e:/f:/g:/h:/i:/j");
        states.put("api", new ServiceState.Stopped());

        var f = new ProgramMissingRule().evaluate(ctx(API), "api").getFirst();

        assertThat(f.evidence()).containsExactly("searched PATH: /a:/b:/c:/d:/e:/f:/g:/h:...");
    }

    // ---- missing settings ----

    @Test
    void missingSettingsComeFromTheServicesOrTheProjectsEnvExample() throws Exception {
        String yaml = "version: 1\nservices:\n"
                + "  api:\n    command: node s.js\n    directory: api\n    environment: { SET_HERE: x }\n"
                + "  db: { container: { image: postgres:18 } }\n";
        machine.files.put(ROOT.resolve(".env.example"), "export SET_HERE=\nA_KEY=\nA_KEY=\nFROM_SHELL=\n# NOT=this\n");
        shellEnv = Map.of("PATH", "/usr/bin", "FROM_SHELL", "1");

        var f = new MissingEnvRule().evaluate(ctx(yaml), "api");

        assertThat(f).singleElement().satisfies(x -> {
            assertThat(x.title()).isEqualTo("Setting A_KEY is not set");
            assertThat(x.explanation())
                    .contains("expects A_KEY, but no environment")
                    .endsWith("sets it.");
            assertThat(x.evidence()).containsExactly(ROOT.resolve(".env.example") + " lists: A_KEY");
        });
        assertThat(new MissingEnvRule().evaluate(ctx(yaml), "db"))
                .as("containers: not checked")
                .isEmpty();
    }

    @Test
    void aDotEnvNextToTheExampleCountsBecauseTheAppLoadsItItself() throws Exception {
        // node-express-boilerplate: dotenv reads .env, which devdesk.yaml doesn't list under env_files.
        String yaml = "version: 1\nservices:\n  api: { command: node s.js, directory: api }\n";
        machine.files.put(ROOT.resolve("api/.env.example"), "PORT=\nJWT_SECRET=\nSMTP_HOST=\n");
        machine.files.put(ROOT.resolve("api/.env"), "PORT=3000\nexport JWT_SECRET=x\n");

        var f = new MissingEnvRule().evaluate(ctx(yaml), "api");

        assertThat(f).singleElement().satisfies(x -> assertThat(x.title()).isEqualTo("Setting SMTP_HOST is not set"));
    }

    @Test
    void severalMissingSettingsAndTheServicesOwnFileWins() throws Exception {
        String yaml = "version: 1\nservices:\n  api: { command: node s.js, directory: api }\n";
        machine.files.put(ROOT.resolve("api/.env.example"), "ONE=\nTWO=\n");
        machine.files.put(ROOT.resolve(".env.example"), "OTHER=\n");

        var f = new MissingEnvRule().evaluate(ctx(yaml), "api").getFirst();

        assertThat(f.title()).isEqualTo("2 settings are not set");
        assertThat(f.explanation()).contains("expects ONE, TWO").endsWith("sets them.");
        assertThat(f.evidence()).containsExactly(ROOT.resolve("api/.env.example") + " lists: ONE, TWO");
    }

    @Test
    void noExampleFileOrEverythingSetMeansNoFinding() throws Exception {
        assertThat(new MissingEnvRule().evaluate(ctx(API), "api")).isEmpty();
        machine.files.put(ROOT.resolve(".env.example"), "PATH=\n");
        assertThat(new MissingEnvRule().evaluate(ctx(API), "api")).isEmpty();
    }

    // ---- killed from outside ----

    @Test
    void aSigkillDevDeskDidntSendIsExplainedPerKindOfService() throws Exception {
        String yaml = "version: 1\nservices:\n  api: { command: x }\n  db: { container: { image: postgres:18 } }\n";
        var killed = ExitStatus.of(137);
        history.put("api", history(List.of(), List.of(killed), List.of(), false, Set.of()));
        history.put("db", history(List.of(), List.of(killed), List.of(), false, Set.of()));

        assertThat(new KilledFromOutsideRule()
                        .evaluate(ctx(yaml), "api")
                        .getFirst()
                        .actions()
                        .getFirst())
                .contains("journalctl -k");
        assertThat(new KilledFromOutsideRule()
                        .evaluate(ctx(yaml), "db")
                        .getFirst()
                        .actions()
                        .getFirst())
                .contains("docker inspect devdesk-")
                .contains("OOMKilled");

        // DevDesk's own SIGKILL (a stop), or another way of ending: not this rule.
        history.put("api", new ServiceHistory(List.of(), List.of(killed), List.of(), List.of(), false, Set.of(), true));
        assertThat(new KilledFromOutsideRule().evaluate(ctx(yaml), "api")).isEmpty();
        history.put("api", history(List.of(), List.of(ExitStatus.of(1)), List.of(), false, Set.of()));
        assertThat(new KilledFromOutsideRule().evaluate(ctx(yaml), "api")).isEmpty();
        history.put("api", history(List.of(), List.of(ExitStatus.of(143)), List.of(), false, Set.of()));
        assertThat(new KilledFromOutsideRule().evaluate(ctx(yaml), "api")).isEmpty();
        history.put("api", ServiceHistory.EMPTY);
        assertThat(new KilledFromOutsideRule().evaluate(ctx(yaml), "api")).isEmpty();
    }

    // ---- who holds a port ----

    @Test
    void portOwnersRecognisesYourOwnProcessInTheProjectFolder() throws Exception {
        var p = ctx(API).project();
        machine.processes.put(
                4121L,
                new ProcessInfo(
                        4121, 1, "npm run dev", Optional.of(ROOT.resolve("api")), Optional.of(NOW.minusSeconds(7200))));
        machine.processes.put(
                5000L, new ProcessInfo(5000, 1, "node other.js", Optional.of(Path.of("/elsewhere")), Optional.empty()));

        var mine = PortOwners.explain(
                new Listener(3000, "127.0.0.1", 1000, Optional.of(4121L), Optional.of("npm")), machine, p, NOW);
        var other = PortOwners.explain(
                new Listener(3000, "127.0.0.1", 1000, Optional.of(5000L), Optional.of("node other.js")),
                machine,
                p,
                NOW);
        var root = PortOwners.explain(
                new Listener(3000, "127.0.0.1", 0, Optional.empty(), Optional.empty()), machine, p, NOW);

        assertThat(mine.insideProject()).isTrue();
        assertThat(mine.who())
                .isEqualTo("your own `npm run dev` (PID 4121), running in this project's folder api/ for 2 h."
                        + " DevDesk didn't start it: it was probably started by hand in another terminal");
        assertThat(mine.fix()).isEqualTo("stop it in that terminal (Ctrl+C), or: kill 4121");
        assertThat(other.insideProject()).isFalse();
        assertThat(other.who()).isEqualTo("PID 5000 (node other.js), which is not part of this project");
        assertThat(root.who()).startsWith("a process of another user (uid 0)");
    }

    @Test
    void agesReadNaturally() {
        assertThat(PortOwners.age(Duration.ofSeconds(-5))).isEqualTo("0 s");
        assertThat(PortOwners.age(Duration.ofSeconds(119))).isEqualTo("119 s");
        assertThat(PortOwners.age(Duration.ofSeconds(120))).isEqualTo("2 min");
        assertThat(PortOwners.age(Duration.ofSeconds(7199))).isEqualTo("119 min");
        assertThat(PortOwners.age(Duration.ofSeconds(7200))).isEqualTo("2 h");
        assertThat(PortOwners.age(Duration.ofSeconds(172_799))).isEqualTo("47 h");
        assertThat(PortOwners.age(Duration.ofSeconds(172_800))).isEqualTo("2 days");
    }
}
