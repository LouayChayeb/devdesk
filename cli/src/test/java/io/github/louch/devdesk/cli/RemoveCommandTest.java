package io.github.louch.devdesk.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.louch.devdesk.linux.trust.TrustStore;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** {@code devdesk remove}: what it accepts as a project. */
class RemoveCommandTest {

    @TempDir
    Path dir;

    @Test
    void aFreshCloneWithoutItsEnvFileCanStillBeRemoved() throws Exception {
        // Found on hoppscotch: its devdesk.yaml names .env, which Set up makes from .env.example. Before Set up ran,
        // Remove refused ("file .env not found"), so the folder DevDesk had cloned couldn't be removed with DevDesk.
        Path shop = Files.createDirectories(dir.resolve("shop"));
        Files.writeString(shop.resolve("devdesk.yaml"), """
                version: 1
                name: shop
                services:
                  api: { command: node server.js, env_files: [.env] }
                """);
        Files.writeString(shop.resolve(".env.example"), "DATABASE_URL=\n");
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        // No Docker here on purpose: only what this machine holds for the project is looked for.
        Map<String, String> env = Map.of(
                "HOME",
                dir.toString(),
                "XDG_STATE_HOME",
                dir.resolve("state").toString(),
                "DOCKER_HOST",
                "unix://" + dir.resolve("no-docker.sock"),
                "PATH",
                "/usr/bin:/bin");
        var command = new RemoveCommand(
                env,
                new TrustStore(dir.resolve("trust.txt")),
                new BufferedReader(new StringReader("")),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));

        int code = new CommandLine(command).execute("--yes", shop.toString());

        assertThat(err.toString(StandardCharsets.UTF_8)).doesNotContain("not found in");
        assertThat(code).isNotEqualTo(ProjectFile.INVALID);
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("shop");
    }
}
