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

/** A real MySQL or MariaDB in a throwaway container. Skips the test class when Docker isn't available. */
final class TestMysql implements AutoCloseable {

    final DatabaseTarget target;
    private final String name;

    private TestMysql(String name, DatabaseTarget target) {
        this.name = name;
        this.target = target;
    }

    /** {@code image} is "mysql:9.5" or "mariadb:11"; both read MYSQL_ROOT_PASSWORD and MYSQL_DATABASE. */
    static TestMysql start(String label, String image, String... setup) throws Exception {
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
                        "MYSQL_ROOT_PASSWORD=dev",
                        "-e",
                        "MYSQL_DATABASE=shop",
                        "-p",
                        "127.0.0.1::3306",
                        image)
                .orElseThrow();
        String mapped = run("docker", "port", name, "3306/tcp").orElseThrow().strip();
        int port = Integer.parseInt(mapped.substring(mapped.lastIndexOf(':') + 1));
        var target = new DatabaseTarget("mysql", DatabaseTarget.Kind.MYSQL, "127.0.0.1", port, "root", "dev", "shop");
        // The image starts a temporary server for its first-time setup, then the real one: wait for a real login.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        while (true) {
            try (Connection c = connect(target);
                    Statement st = c.createStatement()) {
                for (String sql : setup) {
                    st.execute(sql);
                }
                return new TestMysql(name, target);
            } catch (SQLException e) {
                if (System.nanoTime() > deadline) {
                    throw e;
                }
                Thread.sleep(1000);
            }
        }
    }

    static Connection connect(DatabaseTarget t) throws SQLException {
        return DriverManager.getConnection(
                "jdbc:mariadb://" + t.host() + ":" + t.port() + "/" + t.database() + "?allowPublicKeyRetrieval=true",
                t.user(),
                t.password());
    }

    @Override
    public void close() {
        run("docker", "rm", "-f", "-v", name);
    }

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
