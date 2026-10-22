package io.github.louch.devdesk.data;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.louch.devdesk.core.http.HttpCall;
import io.github.louch.devdesk.core.http.LiveSockets;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Against a tiny echo server written here: the WebSocket handshake and text frames, nothing more. */
class JdkWebSocketsTest {

    private final LinkedBlockingQueue<String> seen = new LinkedBlockingQueue<>();

    private final LiveSockets.Listener listener = new LiveSockets.Listener() {
        @Override
        public void opened(String protocol) {
            seen.add("opened");
        }

        @Override
        public void message(String text, boolean binary) {
            seen.add("in " + text);
        }

        @Override
        public void closed(int code, String reason) {
            seen.add("closed " + code);
        }

        @Override
        public void failed(String why) {
            seen.add("failed " + why);
        }
    };

    @Test
    void messagesGoBothWaysAndHeadersReachTheHandshake() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            LinkedBlockingQueue<String> request = new LinkedBlockingQueue<>();
            Thread.ofVirtual().start(() -> echo(server, request));
            var c = new JdkWebSockets()
                    .open(
                            "ws://127.0.0.1:" + server.getLocalPort() + "/live",
                            List.of(new HttpCall.Header("Authorization", "Bearer t0k")),
                            listener);

            assertThat(seen.poll(5, TimeUnit.SECONDS)).isEqualTo("opened");
            c.send("hello");
            assertThat(seen.poll(5, TimeUnit.SECONDS)).isEqualTo("in echo: hello");
            assertThat(request.poll(1, TimeUnit.SECONDS)).contains("Authorization: Bearer t0k");
            c.close();
        }
    }

    @Test
    void aClosedPortFailsWithAPlainReason() throws Exception {
        int free;
        try (ServerSocket s = new ServerSocket(0)) {
            free = s.getLocalPort();
        }
        new JdkWebSockets().open("ws://127.0.0.1:" + free + "/", List.of(), listener);

        assertThat(seen.poll(5, TimeUnit.SECONDS)).startsWith("failed connection refused");
    }

    /** Accepts one client, answers the handshake, then echoes each text frame back. */
    private static void echo(ServerSocket server, LinkedBlockingQueue<String> request) {
        try (Socket s = server.accept()) {
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            StringBuilder head = new StringBuilder();
            while (!head.toString().endsWith("\r\n\r\n")) {
                head.append((char) in.read());
            }
            request.add(head.toString());
            String key = head.toString()
                    .lines()
                    .filter(l -> l.toLowerCase().startsWith("sec-websocket-key:"))
                    .findFirst()
                    .orElseThrow()
                    .substring(18)
                    .strip();
            // The accept value: SHA-1 of the key plus the protocol's fixed GUID, in base64 (RFC 6455).
            String accept = Base64.getEncoder()
                    .encodeToString(MessageDigest.getInstance("SHA-1")
                            .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
                                    .getBytes(StandardCharsets.US_ASCII)));
            out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                            + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            while (true) {
                int b0 = in.read();
                if (b0 < 0 || (b0 & 0x0f) == 8) {
                    return; // closed, or a close frame
                }
                int len = in.read() & 0x7f; // short messages only; client frames are always masked
                byte[] mask = in.readNBytes(4);
                byte[] data = in.readNBytes(len);
                for (int i = 0; i < len; i++) {
                    data[i] ^= mask[i % 4];
                }
                byte[] reply = ("echo: " + new String(data, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
                out.write(0x81);
                out.write(reply.length);
                out.write(reply);
                out.flush();
            }
        } catch (IOException | java.security.NoSuchAlgorithmException e) {
            // the test only looks at what the client saw
        }
    }
}
