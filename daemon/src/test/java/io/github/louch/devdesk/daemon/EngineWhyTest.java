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
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The app's "Why" while a project runs: a service that crashed must be explained with what it printed. Found on a
 * real project (MySQL without a password setting), where the app said "it printed nothing" and the CLI didn't.
 */
class EngineWhyTest {

    @TempDir
    Path state;

    @TempDir
    Path project;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void whyQuotesTheLastLinesOfAServiceThatCrashed() throws Exception {
        Files.writeString(project.resolve("devdesk.yaml"), """
                version: 1
                name: why-test
                services:
                  db:
                    command: sh -c 'echo "You need to specify MYSQL_ROOT_PASSWORD" >&2; exit 1'
                """);
        var builder = new ProcessBuilder(
                        "java",
                        "--enable-native-access=ALL-UNNAMED",
                        "-cp",
                        System.getProperty("java.class.path"),
                        Main.class.getName())
                .redirectError(ProcessBuilder.Redirect.INHERIT);
        builder.environment().put("XDG_STATE_HOME", state.toString());
        Process engine = builder.start();
        try {
            var reader = new BufferedReader(new InputStreamReader(engine.getInputStream()));
            Map<?, ?> hello = ApiJson.MAPPER.readValue(reader.readLine(), Map.class);
            String base = "http://127.0.0.1:" + hello.get("port") + "/api/v1";
            String token = (String) hello.get("token");
            post(base + "/project/open", token, "{\"path\":\"" + project + "\"}");
            post(base + "/project/trust", token, "{}");
            post(base + "/project/up", token, "{}");

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!get(base + "/project", token).body().contains("\"label\":\"crashed\"")) {
                assertThat(System.nanoTime()).as("db never crashed").isLessThan(deadline);
                Thread.sleep(100);
            }

            String why = get(base + "/project/why", token).body();

            assertThat(why).contains("db crashed").contains("You need to specify MYSQL_ROOT_PASSWORD");
            assertThat(why).doesNotContain("it printed nothing");
        } finally {
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
}
