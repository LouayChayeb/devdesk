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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** {@code devdesk once}: a command from a README, pasted once, saved as a one-time card. */
class OnceCommandTest {

    @TempDir
    Path dir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int once(String answers, String... args) {
        var command = new OnceCommand(
                new TrustStore(dir.resolve("trust.txt")),
                new BufferedReader(new StringReader(answers)),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8),
                null); // never started: every run here says --no-start or no
        return new CommandLine(command).execute(args);
    }

    private Path project() throws Exception {
        Path shop = Files.createDirectories(dir.resolve("shop"));
        Files.writeString(shop.resolve("devdesk.yaml"), """
                version: 1
                services:
                  db:
                    container: { image: "postgres:18", ports: ["5432:5432"] }
                    health: { type: tcp, port: 5432 }
                  api:
                    command: node server.js
                    ports: [3000]
                    depends_on: { db: { condition: healthy } }
                """);
        return shop.resolve("devdesk.yaml");
    }

    @Test
    void thePastedCommandIsSavedAsAOneTimeCardTheServiceWaitsFor() throws Exception {
        Path file = project();

        int code = once("", "--yes", "--no-start", "-f", file.toString(), "api", "npx", "prisma", "migrate", "deploy");

        assertThat(code).isZero();
        assertThat(out.toString())
                .contains("Adds the one-time card \"api-prepare\"")
                .contains("before: api");
        String saved = Files.readString(file);
        assertThat(saved)
                .contains("api-prepare:")
                .contains("npx prisma migrate deploy")
                .contains("completed_successfully");
        // What's saved is a valid project that DevDesk can load.
        assertThat(ProjectFile.load(file, java.util.Map.of(), new PrintStream(err)))
                .as(() -> saved + "\n" + err)
                .isPresent();
    }

    @Test
    void noSaveWithoutAYesAndAnUnknownServiceIsNamed() throws Exception {
        Path file = project();
        String before = Files.readString(file);

        assertThat(once("n\n", "-f", file.toString(), "api", "rails", "db:setup"))
                .isEqualTo(1);
        assertThat(Files.readString(file)).isEqualTo(before);

        assertThat(once("", "--yes", "-f", file.toString(), "web", "rails", "db:setup"))
                .isEqualTo(1);
        assertThat(err.toString()).contains("no service \"web\"").contains("Services: db, api");
    }
}
