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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DiagnosisEngineTest {

    private static final java.time.Instant NOW = java.time.Instant.parse("2026-10-04T15:00:00Z");

    private static final Path ROOT = Path.of("/work/shop");
    private final FakeIntrospection machine = new FakeIntrospection();
    private final Map<String, ServiceState> states = new HashMap<>();
    private final Map<String, ServiceHistory> history = new HashMap<>();
    private final DiagnosisEngine engine = DiagnosisEngine.standard();
    private ProjectSpec project;

    private DiagnosisContext ctx(String yaml) throws Exception {
        project = ConfigParser.parse(yaml, ROOT, Map.of(), f -> Optional.empty());
        return new DiagnosisContext(
                project, states, history, Map.of("PATH", "/usr/bin:/bin", "HOME", "/home/me"), machine, NOW);
    }

    private static ServiceHistory exitedWith(int code, OutputLine... lines) {
        return new ServiceHistory(
                List.of(lines), List.of(ExitStatus.of(code)), List.of(), List.of(), false, Set.of(), false);
    }

    private static List<String> titles(List<Finding> findings) {
        return findings.stream().map(Finding::title).toList();
    }

    @Test
    void preflightNamesWhoHoldsATakenPort() throws Exception {
        machine.listeners.put(
                3001, new Listener(3001, "127.0.0.1", 1000, Optional.of(4121L), Optional.of("node old.js")));
        machine.programs.put("npm", Path.of("/usr/bin/npm"));

        var findings = engine.preflight(ctx("""
                version: 1
                services:
                  api: { command: npm start, ports: [3001] }
                """));

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.title()).isEqualTo("Port 3001 is already in use");
            assertThat(f.explanation()).contains("PID 4121 (node old.js)");
            assertThat(f.actions()).contains("Free it with: kill 4121");
        });
    }

    @Test
    void preflightFindsAProgramThatIsNotOnPath() throws Exception {
        machine.programs.put("python3", Path.of("/usr/bin/python3"));

        var findings = engine.preflight(ctx("""
                version: 1
                services:
                  api: { command: npm run dev }
                  worker: { command: PYTHONPATH=src python3 worker.py }
                  job: { command: "cd jobs && ./run.sh" }
                """));

        assertThat(titles(findings)).containsExactly("Program `npm` is not installed or not on PATH");
        assertThat(findings.getFirst().evidence()).containsExactly("searched PATH: /usr/bin:/bin");
        assertThat(findings.getFirst().actions()).anyMatch(a -> a.contains("which npm"));
    }

    @Test
    void exit127IsExplainedByTheProgramRuleNotAsAGenericCrash() throws Exception {
        states.put("api", new ServiceState.Crashed(ExitStatus.of(127), 0));
        history.put("api", exitedWith(127, new OutputLine(StreamKind.STDERR, "sh: 1: nmp: not found")));

        var findings = engine.diagnose(ctx("""
                version: 1
                services:
                  api: { command: nmp start }
                """), "api");

        assertThat(titles(findings)).containsExactly("Program `nmp` is not installed or not on PATH");
    }

    @Test
    void sigkillNobodyAtDevDeskSentPointsAtTheOutOfMemoryKiller() throws Exception {
        states.put("api", new ServiceState.Crashed(ExitStatus.of(137), 0));
        history.put("api", exitedWith(137));

        var findings = engine.diagnose(ctx("""
                version: 1
                services:
                  api: { command: java -jar api.jar }
                """), "api");

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.title()).isEqualTo("api was killed from outside DevDesk");
            assertThat(f.explanation()).contains("out-of-memory killer");
            assertThat(f.actions().getFirst()).contains("journalctl -k");
        });
    }

    @Test
    void crashShowsTheLastErrorLines() throws Exception {
        states.put("api", new ServiceState.Crashed(ExitStatus.of(1), 0));
        history.put(
                "api",
                exitedWith(
                        1,
                        new OutputLine(StreamKind.STDOUT, "starting..."),
                        new OutputLine(StreamKind.STDERR, "Error: connect ECONNREFUSED 127.0.0.1:5432")));

        var findings = engine.diagnose(ctx("""
                version: 1
                services:
                  api: { command: node server.js }
                """), "api");

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.title()).isEqualTo("api crashed");
            assertThat(f.evidence()).containsExactly("stderr | Error: connect ECONNREFUSED 127.0.0.1:5432");
        });
    }

    @Test
    void healthCheckOnTheWrongPortIsSpotted() throws Exception {
        states.put("api", new ServiceState.Unhealthy(4121, 0, "connection refused (3 checks in a row)"));
        history.put(
                "api",
                new ServiceHistory(
                        List.of(),
                        List.of(),
                        List.of(new ProbeResult.Failed("GET http://127.0.0.1:3001/health: connection refused")),
                        List.of(),
                        false,
                        Set.of(4121L),
                        false));
        machine.listeners.put(3000, new Listener(3000, "127.0.0.1", 1000, Optional.of(4121L), Optional.of("node")));

        var findings = engine.diagnose(ctx("""
                version: 1
                services:
                  api:
                    command: node server.js
                    health: { type: http, url: http://127.0.0.1:3001/health }
                """), "api");

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.title()).isEqualTo("api never became healthy");
            assertThat(f.explanation()).contains("It listens on [3000], but the check waits on port 3001");
            assertThat(f.evidence()).contains("api (PIDs [4121]) listens on ports [3000]");
        });
    }

    @Test
    void missingSettingsAreNamedButValuesNeverShown() throws Exception {
        machine.programs.put("node", Path.of("/usr/bin/node"));
        machine.files.put(
                ROOT.resolve(".env.example"),
                "DATABASE_URL=postgres://user:secret@localhost/db\nHOME=x\nexport JWT_SECRET=change-me\n");

        var findings = engine.preflight(ctx("""
                version: 1
                services:
                  api: { command: node server.js }
                """));

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.title()).isEqualTo("2 settings are not set");
            assertThat(f.explanation())
                    .contains("DATABASE_URL, JWT_SECRET")
                    .doesNotContain("secret@")
                    .doesNotContain("change-me");
            assertThat(f.evidence().toString()).doesNotContain("secret@").doesNotContain("change-me");
        });
    }

    @Test
    void blockedServiceIsExplainedRootCauseFirst() throws Exception {
        states.put("db", new ServiceState.Crashed(ExitStatus.of(1), 0));
        history.put(
                "db", exitedWith(1, new OutputLine(StreamKind.STDERR, "FATAL: data directory has wrong ownership")));
        states.put("api", new ServiceState.Blocked("db crashed", "db"));
        states.put("web", new ServiceState.Blocked("api is blocked: db crashed", "api"));

        var findings = engine.diagnose(ctx("""
                version: 1
                services:
                  db: { command: postgres }
                  api: { command: x, depends_on: [db] }
                  web: { command: y, depends_on: [api] }
                """), "web");

        assertThat(titles(findings)).containsExactly("db crashed", "api did not start", "web did not start");
        assertThat(findings.getLast().evidence()).containsExactly("dependency chain: db ← api ← web");
    }

    @Test
    void aBrokenRuleDoesNotHideTheOthers() throws Exception {
        DiagnosisRule broken = new DiagnosisRule() {
            public String id() {
                return "broken";
            }

            public List<Finding> evaluate(DiagnosisContext c, String s) {
                throw new IllegalStateException("boom");
            }
        };
        machine.listeners.put(80, new Listener(80, "0.0.0.0", 0, Optional.empty(), Optional.empty()));

        var findings = new DiagnosisEngine(List.of(broken, new PortConflictRule())).preflight(ctx("""
                version: 1
                services:
                  web: { command: nginx, ports: [80] }
                """));

        assertThat(titles(findings)).containsExactly("Rule broken failed", "Port 80 is already in use");
        assertThat(findings.getLast().explanation()).contains("a process of another user (uid 0)");
    }

    @Test
    void aBlockedLoopStopsInsteadOfWalkingForever() throws Exception {
        // Shouldn't happen (the graph has no cycles) but states arrive from many threads: never hang on them.
        states.put("api", new ServiceState.Blocked("web is blocked", "web"));
        states.put("web", new ServiceState.Blocked("api is blocked", "api"));

        var c = ctx("""
                version: 1
                services:
                  api: { command: x }
                  web: { command: y }
                """);

        assertThat(DiagnosisEngine.causeChain(c, "web")).containsExactly("api", "web");
        assertThat(DiagnosisEngine.causeChain(c, "nobody")).containsExactly("nobody");
    }

    @Test
    void watchRunsOnlyTheContinuousRules() throws Exception {
        DiagnosisRule slow = rule("slow", true);
        DiagnosisRule instant = rule("instant", false);
        var c = ctx("""
                version: 1
                services:
                  api: { command: x }
                """);

        var engine = new DiagnosisEngine(List.of(slow, instant));

        assertThat(titles(engine.watch(c, "api"))).containsExactly("slow on api");
        assertThat(titles(engine.explain(c, "api"))).containsExactly("slow on api", "instant on api");
    }

    private static DiagnosisRule rule(String id, boolean continuous) {
        return new DiagnosisRule() {
            public String id() {
                return id;
            }

            public boolean continuous() {
                return continuous;
            }

            public List<Finding> evaluate(DiagnosisContext c, String s) {
                return List.of(
                        new Finding(id, Finding.Severity.WARNING, s, id + " on " + s, "x", List.of(), List.of()));
            }
        };
    }
}
