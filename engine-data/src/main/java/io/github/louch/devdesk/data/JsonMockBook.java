package io.github.louch.devdesk.data;

import io.github.louch.devdesk.core.http.HttpCall;
import io.github.louch.devdesk.core.http.MockBook;
import io.github.louch.devdesk.core.http.MockRoutes;
import io.github.louch.devdesk.core.project.ProjectSpec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Mock routes of one project: {@code $XDG_STATE_HOME/devdesk/mocks/<project-key>.json}. */
public final class JsonMockBook implements MockBook {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Path file;

    public JsonMockBook(Path file) {
        this.file = file;
    }

    public static JsonMockBook forProject(Map<String, String> env, ProjectSpec project) {
        return new JsonMockBook(StateFolder.of(env).resolve("mocks").resolve(project.key() + ".json"));
    }

    @Override
    public synchronized List<MockRoutes.Route> load() {
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            List<MockRoutes.Route> out = new ArrayList<>();
            for (JsonNode n : JSON.readTree(Files.readString(file)).path("routes")) {
                List<HttpCall.Header> headers = new ArrayList<>();
                for (JsonNode h : n.path("headers")) {
                    headers.add(new HttpCall.Header(
                            h.path("name").asString(), h.path("value").asString("")));
                }
                out.add(new MockRoutes.Route(
                        n.path("id").asString(),
                        n.path("method").asString("GET"),
                        n.path("path").asString("/"),
                        n.path("status").asInt(200),
                        headers,
                        n.path("body").asString(""),
                        Duration.ofMillis(n.path("delayMs").asLong(0))));
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized void store(List<MockRoutes.Route> routes) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (MockRoutes.Route r : routes) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.id());
            m.put("method", r.method());
            m.put("path", r.path());
            m.put("status", r.status());
            m.put(
                    "headers",
                    r.headers().stream()
                            .map(h -> Map.of("name", h.name(), "value", h.value()))
                            .toList());
            m.put("body", r.body());
            m.put("delayMs", r.delay().toMillis());
            list.add(m);
        }
        try {
            Files.createDirectories(file.getParent());
            // Temporary file, then rename: a crash never leaves half a file.
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("routes", list)));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
