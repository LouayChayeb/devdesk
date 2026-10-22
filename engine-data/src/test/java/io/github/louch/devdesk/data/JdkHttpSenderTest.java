package io.github.louch.devdesk.data;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.github.louch.devdesk.core.http.HttpCall;
import io.github.louch.devdesk.core.http.HttpSender.Outcome;
import io.github.louch.devdesk.core.http.HttpSender.Reason;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Against a tiny HTTP server from the JDK, on a free port of 127.0.0.1. */
class JdkHttpSenderTest {

    private HttpServer server;
    private String base;
    private final JdkHttpSender sender = new JdkHttpSender();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/echo", ex -> {
            byte[] in = ex.getRequestBody().readAllBytes();
            String answer = ex.getRequestMethod() + " " + ex.getRequestHeaders().getFirst("X-Test") + " "
                    + new String(in, StandardCharsets.UTF_8);
            byte[] out = answer.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/plain");
            ex.sendResponseHeaders(201, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        // Echoes what a form handler would look at: the content type and the raw body.
        server.createContext("/form", ex -> {
            byte[] in = ex.getRequestBody().readAllBytes();
            String answer = ex.getRequestHeaders().getFirst("Content-Type") + "\n"
                    + ex.getRequestHeaders().getFirst("Authorization") + "\n" + new String(in, StandardCharsets.UTF_8);
            byte[] out = answer.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.createContext("/login", ex -> {
            ex.getResponseHeaders().add("Set-Cookie", "sid=s3cret; Path=/; HttpOnly");
            ex.sendResponseHeaders(204, -1);
            ex.close();
        });
        server.createContext("/me", ex -> {
            String cookie = String.valueOf(ex.getRequestHeaders().getFirst("Cookie"));
            byte[] out = cookie.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(cookie.contains("sid=s3cret") ? 200 : 401, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.createContext("/moved", ex -> {
            ex.getResponseHeaders().add("Location", "/elsewhere");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.createContext("/png", ex -> {
            byte[] out = {(byte) 0x89, 'P', 'N', 'G', (byte) 0xff};
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void methodHeadersAndBodyGoOutAndTheAnswerComesBack() {
        var out = sender.send(new HttpCall(
                "PUT", base + "/echo", List.of(new HttpCall.Header("X-Test", "yes")), Optional.of("{\"a\":1}")));

        assertThat(out).isInstanceOfSatisfying(Outcome.Answered.class, a -> {
            assertThat(a.status()).isEqualTo(201);
            assertThat(a.body()).isEqualTo("PUT yes {\"a\":1}");
            assertThat(a.binary()).isFalse();
            assertThat(a.headers()).contains(new HttpCall.Header("content-type", "text/plain"));
        });
    }

    @Test
    void redirectsAreShownNotFollowed() {
        var out = sender.send(new HttpCall("GET", base + "/moved", List.of(), Optional.empty()));

        assertThat(out).isInstanceOfSatisfying(Outcome.Answered.class, a -> {
            assertThat(a.status()).isEqualTo(302);
            assertThat(a.headers()).contains(new HttpCall.Header("location", "/elsewhere"));
        });
    }

    @Test
    void binaryBodiesAreFlagged() {
        var out = sender.send(new HttpCall("GET", base + "/png", List.of(), Optional.empty()));

        assertThat(out).isInstanceOfSatisfying(Outcome.Answered.class, a -> {
            assertThat(a.binary()).isTrue();
            assertThat(a.size()).isEqualTo(5);
        });
    }

    @Test
    void aClosedPortIsRefused() throws IOException {
        int free;
        try (ServerSocket s = new ServerSocket(0)) {
            free = s.getLocalPort(); // free a moment ago, and nothing listens on it now
        }
        var out = sender.send(new HttpCall("GET", "http://127.0.0.1:" + free + "/", List.of(), Optional.empty()));

        assertThat(out)
                .isInstanceOfSatisfying(
                        Outcome.Failed.class, f -> assertThat(f.reason()).isEqualTo(Reason.REFUSED));
    }

    @Test
    void aServerThatHangsUpWithoutAnsweringIsClosedNotOther() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            // Like a handler that crashed: accept, read the request, close without writing a status line. In a loop:
            // the JDK client retries a GET once on a fresh connection when the first one closes early.
            Thread.ofVirtual().start(() -> {
                while (!s.isClosed()) {
                    try (var c = s.accept()) {
                        c.getInputStream().read(new byte[1024]);
                    } catch (IOException e) {
                        return; // the socket closed at the end of the test
                    }
                }
            });
            var out = sender.send(
                    new HttpCall("GET", "http://127.0.0.1:" + s.getLocalPort() + "/", List.of(), Optional.empty()));

            assertThat(out)
                    .isInstanceOfSatisfying(
                            Outcome.Failed.class, f -> assertThat(f.reason()).isEqualTo(Reason.CLOSED));
        }
    }

    @Test
    void badUrlsAndForbiddenHeadersAreBadRequests() {
        assertThat(sender.send(new HttpCall("GET", "not a url", List.of(), Optional.empty())))
                .isInstanceOfSatisfying(
                        Outcome.Failed.class, f -> assertThat(f.reason()).isEqualTo(Reason.BAD_REQUEST));
        assertThat(sender.send(new HttpCall(
                        "GET", base + "/echo", List.of(new HttpCall.Header("Host", "x")), Optional.empty())))
                .isInstanceOfSatisfying(
                        Outcome.Failed.class, f -> assertThat(f.reason()).isEqualTo(Reason.BAD_REQUEST));
    }

    @Test
    void formsFilesAndAuthHelpers(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws IOException {
        var form = sender.send(new HttpCall(
                "POST",
                base + "/form",
                List.of(),
                new HttpCall.Body.Form(List.of(new HttpCall.Field("city", "São Paulo", false))),
                new HttpCall.Auth.Basic("ada", "pw")));
        assertThat(((Outcome.Answered) form).body())
                .isEqualTo("application/x-www-form-urlencoded\nBasic YWRhOnB3\ncity=S%C3%A3o+Paulo");

        var file = dir.resolve("note.txt");
        java.nio.file.Files.writeString(file, "file content");
        var multipart = sender.send(new HttpCall(
                "POST",
                base + "/form",
                List.of(),
                new HttpCall.Body.Multipart(List.of(
                        new HttpCall.Field("note", file.toString(), true), new HttpCall.Field("a", "1", false))),
                new HttpCall.Auth.Bearer("tok")));
        String body = ((Outcome.Answered) multipart).body();
        assertThat(body).startsWith("multipart/form-data; boundary=devdesk-").contains("\nBearer tok\n");
        assertThat(body)
                .contains("Content-Disposition: form-data; name=\"note\"; filename=\"note.txt\"")
                .contains("file content")
                .contains("name=\"a\"\r\n\r\n1\r\n");

        var missing = sender.send(new HttpCall(
                "POST",
                base + "/form",
                List.of(),
                new HttpCall.Body.Multipart(List.of(new HttpCall.Field("f", "/nope/x.bin", true))),
                new HttpCall.Auth.None()));
        assertThat(missing).isInstanceOfSatisfying(Outcome.Failed.class, f -> {
            assertThat(f.reason()).isEqualTo(Reason.BAD_REQUEST);
            assertThat(f.message()).contains("/nope/x.bin");
        });
    }

    @Test
    void cookiesFromALogInAreSentBackUntilCleared() {
        assertThat(((Outcome.Answered) sender.send(new HttpCall("GET", base + "/me", List.of(), Optional.empty())))
                        .status())
                .isEqualTo(401);
        sender.send(new HttpCall("POST", base + "/login", List.of(), Optional.empty()));
        assertThat(sender.cookies()).singleElement().satisfies(c -> {
            assertThat(c.name()).isEqualTo("sid");
            assertThat(c.httpOnly()).isTrue();
        });
        assertThat(((Outcome.Answered) sender.send(new HttpCall("GET", base + "/me", List.of(), Optional.empty())))
                        .status())
                .isEqualTo(200);

        sender.clearCookies();
        assertThat(sender.cookies()).isEmpty();
    }
}
