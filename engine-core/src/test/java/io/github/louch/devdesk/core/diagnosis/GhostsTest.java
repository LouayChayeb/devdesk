package io.github.louch.devdesk.core.diagnosis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.louch.devdesk.core.config.ConfigParser;
import io.github.louch.devdesk.core.network.Listener;
import io.github.louch.devdesk.core.project.ProjectSpec;
import io.github.louch.devdesk.core.state.ServiceState;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GhostsTest {

    private static final Path ROOT = Path.of("/work/shop");
    private static final Instant NOW = Instant.parse("2026-10-05T10:00:00Z");
    private final FakeIntrospection machine = new FakeIntrospection();

    private ProjectSpec project() throws Exception {
        return ConfigParser.parse("""
                version: 1
                services:
                  api: { command: npm run dev, directory: backend, ports: [3001] }
                """, ROOT, Map.of(), f -> Optional.empty());
    }

    private void process(long pid, long parent, String command, String folder, Instant started) {
        machine.processes.put(
                pid, new ProcessInfo(pid, parent, command, Optional.of(Path.of(folder)), Optional.of(started)));
    }

    @Test
    void forgottenDevServerIsOneGhostWithItsChildrensPorts() throws Exception {
        // The terminal you typed in: bash → npm → sh → node, all inside the project folder.
        process(100, 1, "bash", "/work/shop", NOW.minusSeconds(9000));
        process(200, 100, "npm run dev", "/work/shop/backend", NOW.minusSeconds(7200));
        process(201, 200, "sh -c nest start --watch", "/work/shop/backend", NOW.minusSeconds(7200));
        process(202, 201, "node dist/main.js", "/work/shop/backend", NOW.minusSeconds(7200));
        machine.listeners.put(3001, new Listener(3001, "127.0.0.1", 1000, Optional.of(202L), Optional.of("node")));
        // An editor in the folder: not a dev program and no port, so not a ghost.
        process(300, 1, "vim README.md", "/work/shop", NOW);

        var ghosts = Ghosts.find(project(), machine, Set.of(), Set.of(999L));

        assertThat(ghosts).singleElement().satisfies(g -> {
            // The family's top program; the interactive bash it was typed into is left out.
            assertThat(g.process().command()).isEqualTo("npm run dev");
            assertThat(g.ports()).containsExactly(3001);
            assertThat(g.children()).isEqualTo(2);
        });
    }

    @Test
    void devDeskItselfItsTerminalAndItsServicesAreNotGhosts() throws Exception {
        process(100, 1, "bash", "/work/shop", NOW);
        process(150, 100, "java -cp devdesk.jar Main up", "/work/shop", NOW); // this DevDesk
        process(160, 150, "node server.js", "/work/shop/backend", NOW); // a service it manages

        assertThat(Ghosts.find(project(), machine, Set.of(160L), Set.of(150L))).isEmpty();
    }

    @Test
    void theDevDeskRunningTheProjectAndTheScriptThatStartedItAreNotGhosts() throws Exception {
        process(190, 1, "bash -c ./run-devdesk.sh", "/work/shop", NOW); // a script that runs `devdesk up`
        process(200, 190, "java -cp devdesk.jar Main up", "/work/shop", NOW); // the DevDesk holding the lock
        process(300, 1, "java -cp devdesk.jar Main ps", "/work/shop", NOW); // the one asking

        assertThat(Ghosts.find(project(), machine, Set.of(), Set.of(300L, 200L)))
                .isEmpty();
    }

    @Test
    void portHeldByYourOwnProgramInTheProjectFolderSaysSo() throws Exception {
        process(4121, 1, "npm run dev", "/work/shop/backend", NOW.minusSeconds(7200));
        machine.listeners.put(
                3001, new Listener(3001, "127.0.0.1", 1000, Optional.of(4121L), Optional.of("npm run dev")));
        machine.programs.put("npm", Path.of("/usr/bin/npm"));
        var ctx = new DiagnosisContext(
                project(), Map.of("api", new ServiceState.Stopped()), Map.of(), Map.of(), machine, NOW);

        var finding = new PortConflictRule().evaluate(ctx, "api");

        assertThat(finding).singleElement().satisfies(f -> {
            assertThat(f.explanation())
                    .isEqualTo("api needs port 3001, but it's taken by your own `npm run dev` (PID 4121), "
                            + "running in this project's folder backend/ for 2 h. DevDesk didn't start it: it was probably "
                            + "started by hand in another terminal.");
            assertThat(f.actions().getFirst()).isEqualTo("stop it in that terminal (Ctrl+C), or: kill 4121");
        });
    }

    @Test
    void portHeldByAnotherServiceDevDeskStartedIsNotStartedByHand() throws Exception {
        // Found on excalidraw: its docs card ran `yarn serve`, whose docusaurus took port 3000, the port the app's
        // container needed. Same folder, but DevDesk's own scope holds it: blaming "a terminal" sent people looking
        // for a process they never started.
        process(3664, 3600, "node docusaurus serve", "/work/shop/dev-docs", NOW.minusSeconds(360));
        machine.devdeskProjects.put(3664L, project().key());
        machine.listeners.put(
                3001, new Listener(3001, "127.0.0.1", 1000, Optional.of(3664L), Optional.of("node docusaurus serve")));
        var ctx = new DiagnosisContext(
                project(), Map.of("api", new ServiceState.Stopped()), Map.of(), Map.of(), machine, NOW);

        var finding = new PortConflictRule().evaluate(ctx, "api");

        assertThat(finding).singleElement().satisfies(f -> {
            assertThat(f.explanation())
                    .isEqualTo("api needs port 3001, but it's taken by another service of this project, `node"
                            + " docusaurus serve` (PID 3664), which DevDesk started: two services of this project"
                            + " want the same port.");
            assertThat(f.actions().getFirst()).startsWith("give one of them another port");
        });
    }
}
