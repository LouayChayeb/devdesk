package io.github.louch.devdesk.linux.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.github.louch.devdesk.core.health.ProbeResult;
import io.github.louch.devdesk.core.project.HealthCheckSpec;
import io.github.louch.devdesk.linux.process.LinuxProcessRuntime;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Random;
import org.junit.jupiter.api.Test;

class LocalHealthProbeTest {

    private static final HealthCheckSpec.Timing FAST =
            new HealthCheckSpec.Timing(Duration.ofMillis(100), Duration.ofMillis(500), 1, Duration.ZERO);
    private final LocalHealthProbe probe =
            new LocalHealthProbe(new LinuxProcessRuntime(false, Clock.systemUTC(), new Random()));

    private ProbeResult probe(HealthCheckSpec check) {
        return probe.probe(check, Path.of("/tmp"), System.getenv());
    }

    @Test
    void tcpPassesWhenSomethingListensAndExplainsWhenNot() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            assertThat(probe(new HealthCheckSpec.Tcp("127.0.0.1", server.getLocalPort(), FAST)))
                    .isInstanceOf(ProbeResult.Passed.class);
        }
        int closedPort;
        try (ServerSocket s = new ServerSocket(0)) {
            closedPort = s.getLocalPort(); // free again once closed
        }
        assertThat(probe(new HealthCheckSpec.Tcp("127.0.0.1", closedPort, FAST)).detail())
                .contains("refused the connection");
    }

    @Test
    void httpChecksTheStatusRangeAndNeverFollowsRedirects() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", ex -> {
            ex.sendResponseHeaders(204, -1);
            ex.close();
        });
        server.createContext("/broken", ex -> {
            ex.sendResponseHeaders(503, -1);
            ex.close();
        });
        server.createContext("/login-redirect", ex -> {
            ex.getResponseHeaders().add("Location", "https://example.com/login");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            assertThat(probe(new HealthCheckSpec.Http(URI.create(base + "/ok"), 200, 299, FAST)))
                    .isInstanceOf(ProbeResult.Passed.class);
            assertThat(probe(new HealthCheckSpec.Http(URI.create(base + "/broken"), 200, 399, FAST))
                            .detail())
                    .endsWith("HTTP 503, expected 200-399");
            assertThat(probe(new HealthCheckSpec.Http(URI.create(base + "/login-redirect"), 200, 299, FAST))
                            .detail())
                    .contains("HTTP 302");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void commandPassesOnExitZeroAndShowsItsLastLine() {
        assertThat(probe(new HealthCheckSpec.Command("echo accepting connections", FAST)))
                .isEqualTo(new ProbeResult.Passed(
                        "`echo accepting connections` exited normally (code 0): accepting connections"));
        assertThat(probe(new HealthCheckSpec.Command("echo no response; exit 2", FAST))
                        .detail())
                .contains("error code 2")
                .contains("no response");
    }

    @Test
    void hangingCommandIsStoppedAtTheTimeout() {
        long before = System.nanoTime();
        ProbeResult result = probe(new HealthCheckSpec.Command("sleep 30", FAST));

        assertThat(result.detail()).contains("did not finish within 500 ms");
        assertThat(Duration.ofNanos(System.nanoTime() - before)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void theStatusRangeIncludesBothEnds() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        for (int code : new int[] {199, 200, 299, 300}) {
            server.createContext("/" + code, ex -> {
                ex.sendResponseHeaders(code, -1);
                ex.close();
            });
        }
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        try {
            assertThat(probe(new HealthCheckSpec.Http(URI.create(base + 200), 200, 299, FAST)))
                    .isEqualTo(new ProbeResult.Passed("GET " + base + "200 → HTTP 200"));
            assertThat(probe(new HealthCheckSpec.Http(URI.create(base + 299), 200, 299, FAST)))
                    .isInstanceOf(ProbeResult.Passed.class);
            assertThat(probe(new HealthCheckSpec.Http(URI.create(base + 199), 200, 299, FAST)))
                    .isInstanceOf(ProbeResult.Failed.class);
            assertThat(probe(new HealthCheckSpec.Http(URI.create(base + 300), 200, 299, FAST)))
                    .isInstanceOf(ProbeResult.Failed.class);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void httpExplainsNothingListeningAndNoAnswer() throws Exception {
        int closedPort;
        try (ServerSocket s = new ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
        URI refused = URI.create("http://127.0.0.1:" + closedPort + "/up");
        assertThat(probe(new HealthCheckSpec.Http(refused, 200, 299, FAST)))
                .isEqualTo(new ProbeResult.Failed("GET " + refused + ": connection refused (nothing listening yet?)"));

        // Accepts the connection but never answers: a service stuck at startup.
        try (ServerSocket silent = new ServerSocket(0)) {
            URI stuck = URI.create("http://127.0.0.1:" + silent.getLocalPort() + "/up");
            assertThat(probe(new HealthCheckSpec.Http(stuck, 200, 299, FAST)))
                    .isEqualTo(new ProbeResult.Failed("GET " + stuck + " did not answer within 500 ms"));
        }
    }

    @Test
    void aTimedOutCommandLeavesNoProcessBehind() throws Exception {
        java.nio.file.Path pidFile = java.nio.file.Files.createTempFile("health", ".pid");
        probe(new HealthCheckSpec.Command("sleep 30 & echo $! > " + pidFile + "; wait", FAST));

        long child = Long.parseLong(java.nio.file.Files.readString(pidFile).strip());
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false))
                .as("the check's child sleep was stopped with it")
                .isFalse();
        java.nio.file.Files.delete(pidFile);
    }

    @Test
    void theRemainingKindsAnswerWithoutProbing() {
        assertThat(probe(new HealthCheckSpec.ProcessAlive(FAST))).isEqualTo(new ProbeResult.Passed("process running"));
        assertThat(probe(new HealthCheckSpec.Docker(FAST)))
                .isEqualTo(new ProbeResult.Failed("type: docker checks are answered by Docker, not here"));
        assertThat(probe.probe(new HealthCheckSpec.Command("true", FAST), Path.of("/no/such/folder"), System.getenv()))
                .isInstanceOf(ProbeResult.Failed.class)
                .extracting(ProbeResult::detail)
                .asString()
                .startsWith("Could not start 'health-check' in /no/such/folder");
    }
}
