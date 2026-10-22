package io.github.louch.devdesk.data;

import io.github.louch.devdesk.core.http.HttpCall;
import io.github.louch.devdesk.core.http.HttpCallJson;
import io.github.louch.devdesk.core.http.RequestBook;
import io.github.louch.devdesk.core.project.ProjectSpec;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The API client's file for one project: {@code $XDG_STATE_HOME/devdesk/requests/<project-key>.json}. Readable by
 * its owner only, because saved headers and variables often hold tokens.
 */
public final class JsonRequestBook implements RequestBook {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Path file;

    public JsonRequestBook(Path file) {
        this.file = file;
    }

    public static JsonRequestBook forProject(Map<String, String> env, ProjectSpec project) {
        return new JsonRequestBook(StateFolder.of(env).resolve("requests").resolve(project.key() + ".json"));
    }

    @Override
    public synchronized Book load() {
        if (!Files.exists(file)) {
            return Book.empty();
        }
        try {
            JsonNode root = JSON.readTree(Files.readString(file));
            List<SavedRequest> saved = new ArrayList<>();
            for (JsonNode n : root.path("saved")) {
                saved.add(entry(n));
            }
            Map<String, String> variables = new LinkedHashMap<>();
            root.path("variables")
                    .properties()
                    .forEach(e -> variables.put(e.getKey(), e.getValue().asString()));
            List<Sent> history = new ArrayList<>();
            for (JsonNode n : root.path("history")) {
                history.add(new Sent(
                        Instant.parse(n.path("at").asString()),
                        call(n),
                        n.path("status").isInt() ? Optional.of(n.path("status").asInt()) : Optional.empty(),
                        Duration.ofMillis(n.path("ms").asLong())));
            }
            return new Book(saved, variables, history);
        } catch (IOException | RuntimeException e) { // Jackson 3 throws unchecked JacksonException
            // A damaged file must not break the API client: start empty, and say where the old file is.
            System.err.println("devdesk: could not read " + file + " (" + e.getMessage() + "); starting empty");
            return Book.empty();
        }
    }

    @Override
    public synchronized void store(Book book) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("saved", book.saved().stream().map(JsonRequestBook::entry).toList());
        root.put("variables", book.variables());
        root.put(
                "history",
                book.history().stream()
                        .map(s -> {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("at", s.at().toString());
                            m.putAll(call(s.call()));
                            m.put("status", s.status().orElse(null));
                            m.put("ms", s.took().toMillis());
                            return m;
                        })
                        .toList());
        try {
            Files.createDirectories(file.getParent());
            // Write a new file, then rename it over the old one: a crash mid-write never leaves half a file.
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root));
            try {
                Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException e) {
                // not a POSIX file system: nothing to tighten
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            System.err.println("devdesk: could not save " + file + ": " + e.getMessage());
        }
    }

    /** One saved request as it is written in the file (also in shared collection files). */
    static Map<String, Object> entry(SavedRequest r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("name", r.name());
        m.put("collection", r.collection());
        m.putAll(call(r.call()));
        m.put("checks", HttpCallJson.writeChecks(r.checks()));
        m.put("captures", HttpCallJson.writeCaptures(r.captures()));
        return m;
    }

    static SavedRequest entry(JsonNode n) {
        Map<?, ?> m = JSON.convertValue(n, Map.class);
        return new SavedRequest(
                n.path("id").asString(),
                n.path("name").asString(),
                n.path("collection").asString(""),
                call(n),
                HttpCallJson.readChecks(m.get("checks")),
                HttpCallJson.readCaptures(m.get("captures")));
    }

    /** The request part of an entry; an entry that doesn't parse becomes an empty GET instead of breaking the file. */
    private static HttpCall call(JsonNode n) {
        Map<?, ?> m = JSON.convertValue(n, Map.class);
        return HttpCallJson.read(m)
                .orElse(new HttpCall("GET", n.path("url").asString(""), List.of(), Optional.empty()));
    }

    private static Map<String, Object> call(HttpCall c) {
        return HttpCallJson.write(c);
    }
}
