package io.github.louch.devdesk.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The extras switch: off by default, on by setting or env var, and while off their routes don't exist at all. */
class ExtrasTest {

    /** One route of each extra: recorder, stress lab, remote machine, queues, mock server, S3, Elasticsearch. */
    private static final List<String> EXTRA_ROUTES = List.of(
            "GET /recorder",
            "GET /stress",
            "GET /remote",
            "GET /queues",
            "GET /http/mock",
            "GET /data/x/objects/buckets",
            "GET /data/x/search/indices");

    /** Core routes that must be there either way (the API client stays: it isn't an extra). */
    private static final List<String> CORE_ROUTES =
            List.of("GET /project", "GET /project/verdict", "GET /data", "GET /http", "GET /mail");

    @TempDir
    Path home;

    private Map<String, String> env(String... pairs) {
        Map<String, String> m = new java.util.HashMap<>(Map.of("HOME", home.toString()));
        for (int i = 0; i < pairs.length; i += 2) {
            m.put(pairs[i], pairs[i + 1]);
        }
        return m;
    }

    @Test
    void offByDefault() {
        assertThat(Extras.enabled(env())).isFalse();
    }

    @Test
    void theSavedSettingTurnsThemOnAndKeepsOtherKeys() throws Exception {
        Path file = Extras.file(env());
        Files.createDirectories(file.getParent());
        Files.writeString(file, "theme=dark\n");

        Extras.save(env(), true);

        assertThat(Extras.enabled(env())).isTrue();
        assertThat(Files.readString(file)).contains("theme=dark").contains("extras=on");
        assertThat(file).isEqualTo(home.resolve(".config/devdesk/settings.properties"));
    }

    @Test
    void theEnvVarWinsOverTheSettingAndNonsenseIsIgnored() throws Exception {
        Extras.save(env(), true);

        assertThat(Extras.enabled(env(Extras.ENV, "off"))).isFalse();
        assertThat(Extras.forcedByEnvironment(env(Extras.ENV, "off"))).isTrue();
        assertThat(Extras.enabled(env(Extras.ENV, "maybe"))).isTrue(); // not on/off: the setting decides
        assertThat(Extras.enabled(
                        env("XDG_CONFIG_HOME", home.resolve("elsewhere").toString())))
                .isFalse();
    }

    @Test
    void offMeansTheExtrasRoutesAreNotRegistered() throws Exception {
        try (var engine = Engine.start(home, "off")) {
            for (String route : EXTRA_ROUTES) {
                assertThat(engine.call(route).body()).as(route).contains("not found");
            }
            for (String route : CORE_ROUTES) {
                assertThat(engine.call(route).body()).as(route).doesNotContain("Endpoint");
            }
            assertThat(engine.call("GET /settings").body()).contains("\"extras\":false");
        }
    }

    @Test
    void onMeansTheyAreThere() throws Exception {
        try (var engine = Engine.start(home, "on")) {
            for (String route : EXTRA_ROUTES) {
                // Registered: they answer themselves ("no project is open"), not Javalin's "Endpoint ... not found".
                assertThat(engine.call(route).body()).as(route).doesNotContain("Endpoint");
            }
            assertThat(engine.call("GET /settings").body()).contains("\"extras\":true");
        }
    }

    @Test
    void theAppsToggleSavesTheSettingForTheNextStart() throws Exception {
        try (var engine = Engine.start(home, null)) {
            var r = engine.call("POST /settings/extras", "{\"on\":true}");
            assertThat(r.body()).contains("\"extras\":false").contains("\"extrasSaved\":true");
        }
        try (var engine = Engine.start(home, null)) {
            assertThat(engine.call("GET /stress").body()).doesNotContain("Endpoint");
        }
    }

    /** The real engine as its own program, like the app runs it, with a throwaway home. */
    private record Engine(Process process, String base, String token, HttpClient http) implements AutoCloseable {

        static Engine start(Path home, String extras) throws Exception {
            var builder = new ProcessBuilder(
                            "java",
                            "--enable-native-access=ALL-UNNAMED",
                            "-cp",
                            System.getProperty("java.class.path"),
                            Main.class.getName())
                    .redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.environment().put("HOME", home.toString());
            builder.environment().put("XDG_STATE_HOME", home.resolve("state").toString());
            builder.environment().remove("XDG_CONFIG_HOME");
            builder.environment().remove(Extras.ENV);
            if (extras != null) {
                builder.environment().put(Extras.ENV, extras);
            }
            Process p = builder.start();
            var reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            Map<?, ?> hello = ApiJson.MAPPER.readValue(reader.readLine(), Map.class);
            return new Engine(
                    p,
                    "http://127.0.0.1:" + hello.get("port") + "/api/v1",
                    (String) hello.get("token"),
                    HttpClient.newHttpClient());
        }

        HttpResponse<String> call(String route) throws Exception {
            return call(route, null);
        }

        HttpResponse<String> call(String route, String body) throws Exception {
            String[] parts = route.split(" ", 2);
            var request = HttpRequest.newBuilder(URI.create(base + parts[1]))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .method(
                            parts[0],
                            body == null
                                    ? HttpRequest.BodyPublishers.noBody()
                                    : HttpRequest.BodyPublishers.ofString(body))
                    .build();
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        }

        @Override
        public void close() throws java.io.IOException {
            // Closing its stdin is how the app says goodbye: the engine stops everything and exits.
            process.getOutputStream().close();
            try {
                if (!process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly(); // our own child process, started above
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
    }
}
