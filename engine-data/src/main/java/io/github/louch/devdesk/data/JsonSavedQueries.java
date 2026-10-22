package io.github.louch.devdesk.data;

import io.github.louch.devdesk.core.data.SavedQueries;
import io.github.louch.devdesk.core.project.ProjectSpec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Saved queries of one project: {@code $XDG_STATE_HOME/devdesk/queries/<project-key>.json}. */
public final class JsonSavedQueries implements SavedQueries {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Path file;

    public JsonSavedQueries(Path file) {
        this.file = file;
    }

    public static JsonSavedQueries forProject(Map<String, String> env, ProjectSpec project) {
        return new JsonSavedQueries(StateFolder.of(env).resolve("queries").resolve(project.key() + ".json"));
    }

    @Override
    public synchronized List<SavedQuery> load() {
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            List<SavedQuery> out = new ArrayList<>();
            for (JsonNode n : JSON.readTree(Files.readString(file)).path("queries")) {
                out.add(new SavedQuery(
                        n.path("id").asString(),
                        n.path("service").asString(),
                        n.path("name").asString(),
                        n.path("sql").asString()));
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized void store(List<SavedQuery> queries) {
        List<Map<String, String>> list = new ArrayList<>();
        for (SavedQuery q : queries) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("id", q.id());
            m.put("service", q.service());
            m.put("name", q.name());
            m.put("sql", q.sql());
            list.add(m);
        }
        try {
            Files.createDirectories(file.getParent());
            // Write a temporary file, then rename it over the old one: a crash never leaves half a file.
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("queries", list)));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
