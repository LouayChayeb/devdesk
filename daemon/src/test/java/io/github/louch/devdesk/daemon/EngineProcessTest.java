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
            // The setup wizard on a compose project: scanned and checked, nothing written or started.
            String compose = Path.of("src", "test", "resources", "projects", "compose-shop")
                    .toAbsolutePath()
                    .normalize()
                    .toString();
            var scan = post(base + "/setup/scan", token, "{\"path\":\"" + compose + "\"}");
            assertThat(scan.statusCode()).isEqualTo(200);
            assertThat(scan.body())
                    .contains("\"mode\":\"new\"")
                    // Built as a container by default, reaching "db" by name; the way to run it here is kept too.
                    .contains("\"DATABASE_URL\":\"postgres://postgres:${DB_PASSWORD}@db:5432/shop\"")
                    .contains(
                            "\"native.env.DATABASE_URL\":\"postgres://postgres:${DB_PASSWORD}@127.0.0.1:55450/shop\"");
            Map<?, ?> scanned = ApiJson.MAPPER.readValue(scan.body(), Map.class);
            var preview = post(
                    base + "/setup/preview",
                    token,
                    ApiJson.write(Map.of("path", compose, "draft", scanned.get("draft"))));
            assertThat(preview.statusCode()).isEqualTo(200);
            assertThat(preview.body())
                    .contains("\"problems\":[]")
                    .contains("api:  container compose-shop-api (built from ");
            assertThat(Path.of(compose, "devdesk.yaml")).doesNotExist();
            // A command pasted from the project's docs: a one-time card api waits for, and the draft comes back.
            var once = post(
                    base + "/setup/once",
                    token,
                    ApiJson.write(
                            Map.of("draft", scanned.get("draft"), "service", "api", "command", "npm run migrate")));
            assertThat(once.statusCode()).isEqualTo(200);
            assertThat(once.body())
                    .contains("\"name\":\"api-prepare\"")
                    .contains("\"command\":\"npm run migrate\"")
                    .contains("\"api-prepare\":\"completed_successfully\"");
            assertThat(post(
                                    base + "/setup/once",
                                    token,
                                    ApiJson.write(
                                            Map.of("draft", scanned.get("draft"), "service", "nope", "command", "x")))
                            .statusCode())
                    .isEqualTo(400);
            assertThat(post(base + "/setup/scan", token, "{\"path\":\"/no/such/folder\"}")
                            .statusCode())
                    .isEqualTo(404);

            String project = Path.of("src", "test", "resources", "projects", "three-tier")
                    .toAbsolutePath()
                    .normalize()
                    .toString();
            assertThat(post(base + "/project/open", token, "{\"path\":\"" + project + "\"}")
                            .statusCode())
                    .isEqualTo(200);
            assertThat(post(base + "/project/trust", token, "").statusCode()).isEqualTo(200);
            // The doctor (like `devdesk doctor`): three-tier only needs Python, which this machine has.
            var doctor = get(base + "/project/doctor", token);
            assertThat(doctor.statusCode()).isEqualTo(200);
            assertThat(doctor.body())
                    .contains("\"passed\":[")
                    .contains("Python 3.")
                    .contains("of disk space free");

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

            // The flight recorder's views: the timeline has this session, the machine view answers.
            var history = get(base + "/project/history?hours=1", token);
            assertThat(history.statusCode()).isEqualTo(200);
            assertThat(history.body()).contains("\"kind\":\"session\"").contains("\"service\":\"web\"");
            var machine = get(base + "/project/machine", token);
            assertThat(machine.statusCode()).isEqualTo(200);
            assertThat(machine.body()).contains("\"connections\":").contains("\"ghosts\":[]");
            assertThat(get(base + "/project/history?hours=abc", token).statusCode())
                    .isEqualTo(400);

            // The Ports tab: db's port belongs to this project.
            assertThat(get(base + "/ports", token).body())
                    .contains("\"port\":5433,")
                    .contains("\"owner\":\"this-project\",\"who\":\"db\"");

            // The service drawer: keys and their source, never values, unless explicitly revealed.
            var db = get(base + "/services/db", token);
            assertThat(db.statusCode()).isEqualTo(200);
            assertThat(db.body())
                    .contains("\"key\":\"XDG_STATE_HOME\",\"source\":\"shell\"")
                    .contains("\"probes\":[{\"ok\":")
                    .doesNotContain(state.toString());
            assertThat(post(base + "/services/db/env/XDG_STATE_HOME/reveal", token, "")
                            .body())
                    .contains(state.toString());

            // Stopping api stops web too (it needs api); db keeps running.
            int mark = events.size();
            assertThat(post(base + "/services/api/stop", token, "").statusCode())
                    .isEqualTo(202);
            awaitEvent(events, mark, "web", "stopped");
            awaitEvent(events, mark, "api", "stopped");
            assertThat(alive(pids.getFirst())).as("db still running").isTrue();
            mark = events.size();
            assertThat(post(base + "/services/web/start", token, "").statusCode())
                    .isEqualTo(202);
            awaitEvent(events, mark, "api", "healthy"); // web needs api, so api starts first
            awaitEvent(events, mark, "web", "healthy");

            // The project switcher: opening another project is refused while this one runs, unless the user chose
            // "stop this one and open that one". Then everything is stopped before the other project opens.
            String other = Path.of("src", "test", "resources", "projects", "mixed")
                    .toAbsolutePath()
                    .normalize()
                    .toString();
            assertThat(post(base + "/project/open", token, "{\"path\":\"" + other + "\"}")
                            .statusCode())
                    .isEqualTo(409);
            var switched = post(base + "/project/open", token, "{\"path\":\"" + other + "\",\"stopCurrent\":true}");
            assertThat(switched.statusCode()).as(switched.body()).isEqualTo(200);
            assertThat(switched.body()).contains("\"name\":\"mixed\"");
            assertThat(pids).as("three-tier left running after the switch").noneMatch(EngineProcessTest::alive);

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

    /** Waits for a state event of {@code service} with {@code label}, among the events from index {@code from}. */
    private static void awaitEvent(List<String> events, int from, String service, String label)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        // Copy first: a subList view of a list another thread appends to throws ConcurrentModificationException.
        while (List.copyOf(events).stream()
                .skip(from)
                .noneMatch(e ->
                        e.contains("\"service\":\"" + service + "\"") && e.contains("\"label\":\"" + label + "\""))) {
            assertThat(System.nanoTime())
                    .as("%s never became %s; events: %s", service, label, events)
                    .isLessThan(deadline);
            Thread.sleep(100);
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
