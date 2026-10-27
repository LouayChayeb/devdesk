package io.github.louch.devdesk.data;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.louch.devdesk.core.data.DatabaseTarget;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** A real Postgres in a throwaway container for one test class. Skips the class when Docker isn't available. */
final class TestPostgres implements AutoCloseable {

    final DatabaseTarget target;
    private final String name;

    private TestPostgres(String name, DatabaseTarget target) {
        this.name = name;
        this.target = target;
    }

    /** Starts the container, waits until Postgres accepts connections, then runs {@code setupSql}. */
    static TestPostgres start(String label, String setupSql) throws Exception {
        assumeTrue(run("docker", "info").isPresent(), "Docker is not available");
        TestJvm.reapOrphans(); // containers of test JVMs that were killed mid-test
        String name = "devdesk-test-" + label + "-" + ProcessHandle.current().pid();
        run(
                        "docker",
                        "run",
                        "-d",
                        "--rm",
                        "--name",
                        name,
                        "--label",
                        TestJvm.LABEL + "=" + TestJvm.ID,
                        "-e",
                        "POSTGRES_PASSWORD=dev",
                        "-e",
                        "POSTGRES_DB=shop",
                        "-p",
                        "127.0.0.1::5432",
                        "postgres:18.1")
                .orElseThrow();
        String mapped = run("docker", "port", name, "5432/tcp").orElseThrow().strip(); // "127.0.0.1:49153"
        int port = Integer.parseInt(mapped.substring(mapped.lastIndexOf(':') + 1));
        var target = new DatabaseTarget(
                "postgres", DatabaseTarget.Kind.POSTGRES, "127.0.0.1", port, "postgres", "dev", "shop");
        // Postgres restarts once during first-time setup; wait until a real connection works.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (true) {
            try (Connection c = connect(target);
                    Statement st = c.createStatement()) {
                st.execute(setupSql);
                return new TestPostgres(name, target);
            } catch (SQLException e) {
                if (System.nanoTime() > deadline) {
                    throw e;
                }
                Thread.sleep(500);
            }
        }
    }

    /** A plain writable connection, for checking what the code under test did. */
    static Connection connect(DatabaseTarget t) throws SQLException {
        return DriverManager.getConnection(
                "jdbc:postgresql://" + t.host() + ":" + t.port() + "/" + t.database(), t.user(), t.password());
    }

    @Override
    public void close() {
        run("docker", "rm", "-f", "-v", name);
    }

    /** The command's output, or empty if it failed. */
    private static Optional<String> run(String... command) {
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return p.waitFor() == 0 ? Optional.of(out) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }
}
