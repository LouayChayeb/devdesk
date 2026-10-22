package io.github.louch.devdesk.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.louch.devdesk.core.data.DataException;
import io.github.louch.devdesk.core.data.DatabaseTarget;
import io.github.louch.devdesk.core.data.DocumentBrowser;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Against a real MongoDB (with a root user) in a throwaway container. Skipped when Docker isn't available. */
class MongoBrowserTest {

    private static final String NAME =
            "devdesk-test-mongo-" + ProcessHandle.current().pid();
    private static DatabaseTarget mongo;
    private final MongoBrowser browser = new MongoBrowser();

    @BeforeAll
    static void start() throws Exception {
        assumeTrue(run("docker", "info").isPresent(), "Docker is not available");
        run(
                        "docker",
                        "run",
                        "-d", // no --rm: if it crashes, its logs stay readable; stop() removes it
                        "--name",
                        NAME,
                        "-p",
                        "127.0.0.1::27017",
                        "-e",
                        "MONGO_INITDB_ROOT_USERNAME=root",
                        "-e",
                        "MONGO_INITDB_ROOT_PASSWORD=dev",
                        "mongo:8")
                .orElseThrow();
        String mapped = run("docker", "port", NAME, "27017/tcp").orElseThrow().strip();
        int port = Integer.parseInt(mapped.substring(mapped.lastIndexOf(':') + 1));
        mongo = new DatabaseTarget("mongo", DatabaseTarget.Kind.MONGO, "127.0.0.1", port, "root", "dev", "");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        // The image starts a temporary server to create the user, then the real one: wait until a login works.
        while (true) {
            try {
                new MongoBrowser().databases(mongo);
                break;
            } catch (DataException e) {
                if (System.nanoTime() > deadline) {
                    // Its own output says why (crashed, still initializing...): worth more than "connection refused".
                    throw new IllegalStateException(
                            "mongo:8 wasn't ready after 60 s. Its last output:\n"
                                    + run("docker", "logs", "--tail", "30", NAME)
                                            .orElse("(no logs: it exited)"),
                            e);
                }
                Thread.sleep(500);
            }
        }
    }

    @AfterAll
    static void stop() throws Exception {
        if (mongo != null) {
            run("docker", "rm", "-f", NAME);
        }
    }

    @Test
    void documentsAreFoundEditedAndDeletedAsJson() {
        String id = browser.insert(
                mongo,
                "shop",
                "orders",
                "{\"item\": \"mug\", \"total\": 12.5, \"at\": {\"$date\": \"2026-10-08T10:00:00Z\"}}");
        browser.insert(mongo, "shop", "orders", "{\"item\": \"cap\", \"total\": 40}");
        assertThat(id).contains("$oid");

        assertThat(browser.databases(mongo))
                .extracting(DocumentBrowser.Database::name)
                .containsExactly("shop");
        assertThat(browser.collections(mongo, "shop")).containsExactly(new DocumentBrowser.Collection("orders", 2));
        DocumentBrowser.Page page = browser.find(mongo, "shop", "orders", "{\"total\": {\"$gt\": 20}}", "", 0, 50);
        assertThat(page.matching()).isEqualTo(1);
        assertThat(page.documents()).singleElement().asString().contains("\"cap\"");
        assertThat(browser.find(mongo, "shop", "orders", "", "{\"total\": 1}", 0, 1)
                        .documents()
                        .getFirst())
                .contains("\"mug\"")
                .contains("\"$date\""); // types survive the trip as Extended JSON

        browser.replace(mongo, "shop", "orders", id, "{\"item\": \"big mug\", \"total\": 15}");
        assertThat(browser.find(mongo, "shop", "orders", "{\"item\": \"big mug\"}", "", 0, 5)
                        .matching())
                .isEqualTo(1);
        assertThatThrownBy(() -> browser.replace(mongo, "shop", "orders", id, "{\"_id\": 7, \"item\": \"x\"}"))
                .hasMessageContaining("_id can't be changed");

        assertThat(browser.delete(mongo, "shop", "orders", id)).isTrue();
        assertThat(browser.delete(mongo, "shop", "orders", id)).isFalse();
        assertThatThrownBy(() -> browser.find(mongo, "shop", "orders", "{oops", "", 0, 5))
                .hasMessageContaining("filter isn't valid JSON");
    }

    @Test
    void aWrongPasswordIsExplained() {
        var wrong =
                new DatabaseTarget("mongo", DatabaseTarget.Kind.MONGO, mongo.host(), mongo.port(), "root", "nope", "");

        assertThatThrownBy(() -> browser.databases(wrong)).hasMessageContaining("refused the login of root");
    }

    private static Optional<String> run(String... command) {
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return p.waitFor() == 0 ? Optional.of(out) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
