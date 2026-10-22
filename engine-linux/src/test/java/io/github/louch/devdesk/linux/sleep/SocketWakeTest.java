package io.github.louch.devdesk.linux.sleep;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** Real sockets: the first connection wakes the "service" and reaches it once it listens. */
class SocketWakeTest {

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @Test
    void theFirstConnectionWakesTheServiceAndGetsItsAnswer() throws Exception {
        int port = freePort();
        AtomicBoolean woken = new AtomicBoolean();
        new SocketWake()
                .listen(
                        port,
                        () -> {
                            woken.set(true);
                            // The "service" takes a moment to start, then listens on the same port (now free again).
                            Thread.ofVirtual().start(() -> {
                                try {
                                    Thread.sleep(500);
                                    try (ServerSocket service = new ServerSocket()) {
                                        service.bind(new InetSocketAddress("127.0.0.1", port));
                                        try (Socket c = service.accept()) {
                                            byte[] got = c.getInputStream().readNBytes(4);
                                            c.getOutputStream()
                                                    .write(("echo " + new String(got, StandardCharsets.UTF_8))
                                                            .getBytes(StandardCharsets.UTF_8));
                                        }
                                    }
                                } catch (Exception e) {
                                    throw new IllegalStateException(e);
                                }
                            });
                        },
                        Duration.ofSeconds(10));

        try (Socket client = new Socket("127.0.0.1", port)) {
            client.getOutputStream().write("ping".getBytes(StandardCharsets.UTF_8));
            client.setSoTimeout(10_000);
            String answer = new String(client.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(answer).isEqualTo("echo ping");
        }
        assertThat(woken).isTrue();
    }

    @Test
    void closingGivesThePortBack() throws Exception {
        int port = freePort();
        var waiting = new SocketWake().listen(port, () -> {}, Duration.ofSeconds(1));
        waiting.close();
        try (ServerSocket again = new ServerSocket()) {
            again.bind(new InetSocketAddress("127.0.0.1", port));
            assertThat(again.isBound()).isTrue();
        }
        waiting.close(); // twice is fine
    }
}
