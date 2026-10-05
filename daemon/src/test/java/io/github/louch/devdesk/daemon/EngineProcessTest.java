package io.github.louch.devdesk.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the real engine as its own program, the way the desktop app does, and proves the key promise: when the app
 * goes away (its pipe to the engine closes), every service is stopped and nothing is left running.
 */
class EngineProcessTest {

    @TempDir
    Path state;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void closingTheAppsPipeStopsEveryService() throws Exception {
        var builder = new ProcessBuilder(
                        "java",
                        "--enable-native-access=ALL-UNNAMED",
                        "-cp",
                        System.getProperty("java.class.path"),
                        Main.class.getName())
                .redirectError(ProcessBuilder.Redirect.INHERIT);
        builder.environment().put("XDG_STATE_HOME", state.toString()); // a throwaway trust list, not the real one
        Process engine = builder.start(); // its stdin stays open: this test plays the app
        var reader = new BufferedReader(new InputStreamReader(engine.getInputStream()));
        Map<?, ?> hello = ApiJson.MAPPER.readValue(reader.readLine(), Map.class);
        String base = "http://127.0.0.1:" + hello.get("port") + "/api/v1";
        String token = (String) hello.get("token");

        try {
            String project = Path.of("..", "examples", "three-tier")
                    .toAbsolutePath()
                    .normalize()
                    .toString();
            assertThat(post(base + "/project/open", token, "{\"path\":\"" + project + "\"}")
                            .statusCode())
                    .isEqualTo(200);
            assertThat(post(base + "/project/trust", token, "").statusCode()).isEqualTo(200);

            List<String> events = new CopyOnWriteArrayList<>();
            http.newWebSocketBuilder()
                    .buildAsync(
                            URI.create(base.replace("http", "ws") + "/events?token=" + token),
                            new WebSocket.Listener() {
                                private final StringBuilder text = new StringBuilder();

                                @Override
                                public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                                    text.append(data);
                                    if (last) {
                                        events.add(text.toString());
                                        text.setLength(0);
                                    }
                                    ws.request(1);
                                    return null;
                                }
                            })
                    .get(10, TimeUnit.SECONDS);
            assertThat(post(base + "/project/up", token, "").statusCode()).isEqualTo(202);

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (events.stream()
                    .noneMatch(e -> e.contains("\"service\":\"web\"") && e.contains("\"label\":\"healthy\""))) {
                assertThat(System.nanoTime())
                        .as("web never became healthy; events: %s", events)
                        .isLessThan(deadline);
                Thread.sleep(100);
            }
            assertThat(events.getFirst()).contains("\"type\":\"snapshot\"");
            List<Long> pids = reportedPids(events);
            assertThat(pids).hasSize(3).allMatch(EngineProcessTest::alive);

            // The flight recorder's views (ADR 0008): the timeline has this session, the machine view answers.
            var history = get(base + "/project/history?hours=1", token);
            assertThat(history.statusCode()).isEqualTo(200);
            assertThat(history.body()).contains("\"kind\":\"session\"").contains("\"service\":\"web\"");
            var machine = get(base + "/project/machine", token);
            assertThat(machine.statusCode()).isEqualTo(200);
            assertThat(machine.body()).contains("\"connections\":").contains("\"ghosts\":[]");
            assertThat(get(base + "/project/history?hours=abc", token).statusCode())
                    .isEqualTo(400);

            engine.getOutputStream().close(); // the app is gone: its end of the pipe closes

            assertThat(engine.waitFor(60, TimeUnit.SECONDS)).as("engine exited").isTrue();
            assertThat(pids).as("services left running").noneMatch(EngineProcessTest::alive);
            assertThat(devdeskUnits()).as("systemd scopes left").isZero();
        } finally {
            // Clean up like the app would: close the pipe so the engine stops its services. A force-kill (SIGKILL)
            // gives the engine no chance to run its stop sequence, and the services would be left running.
            engine.getOutputStream().close();
            if (!engine.waitFor(60, TimeUnit.SECONDS)) {
                engine.destroyForcibly();
            }
        }
    }

    private HttpResponse<String> get(String url, String token) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create(url))
                        .header("Authorization", "Bearer " + token)
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String url, String token, String body) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create(url))
                        .header("Authorization", "Bearer " + token)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** PIDs the engine itself reported in its "running" state events: ownership facts, no name matching. */
    private static List<Long> reportedPids(List<String> events) {
        return events.stream()
                .filter(e -> e.contains("\"type\":\"state\""))
                .map(e -> (Map<?, ?>) ((Map<?, ?>) ApiJson.MAPPER.readValue(e, Map.class)).get("state"))
                .filter(st -> st.get("pid") instanceof Number)
                .map(st -> ((Number) st.get("pid")).longValue())
                .distinct()
                .toList();
    }

    private static boolean alive(long pid) {
        // Not ProcessHandle.isAlive(): Java calls a zombie (already dead) alive.
        try {
            String stat = java.nio.file.Files.readString(Path.of("/proc", Long.toString(pid), "stat"));
            return !stat.substring(stat.lastIndexOf(')') + 2).startsWith("Z");
        } catch (java.io.IOException e) {
            return false;
        }
    }

    /** systemd scopes DevDesk created (one per service when systemd is available). */
    private static long devdeskUnits() throws Exception {
        Process p = new ProcessBuilder(
                        "systemctl", "--user", "list-units", "--all", "devdesk-*", "--no-legend", "--plain")
                .start();
        return new String(p.getInputStream().readAllBytes())
                .lines()
                .filter(l -> !l.isBlank())
                .count();
    }
}
