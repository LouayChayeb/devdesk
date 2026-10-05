package io.github.louch.devdesk.linux.history;

import io.github.louch.devdesk.core.history.HistoryEntry;
import io.github.louch.devdesk.core.history.HistoryStore;
import io.github.louch.devdesk.core.project.ProjectSpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import tools.jackson.databind.json.JsonMapper;

/**
 * History on disk: {@code <dir>/<yyyy-MM-dd>.jsonl}, one JSON object per line, one file per UTC day.
 *
 * <p>Why JSON lines: appending a line is cheap and safe (a crash loses at most the line being written), the files
 * are readable with any text tool ({@code grep}, {@code jq}), and old days are deleted by removing a file.
 */
public final class JsonlHistoryStore implements HistoryStore {

    /** Days of history kept per project. */
    static final int KEEP_DAYS = 14;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Path dir;
    private boolean warned;

    public JsonlHistoryStore(Path dir) {
        this.dir = dir;
        deleteOldDays();
    }

    /**
     * {@code $XDG_STATE_HOME/devdesk/history/<name>-<hash>/}. The hash of the project folder keeps two projects with
     * the same name apart.
     */
    public static JsonlHistoryStore forProject(Map<String, String> env, ProjectSpec project) {
        String state = env.getOrDefault("XDG_STATE_HOME", "");
        Path base = state.isBlank() ? Path.of(env.getOrDefault("HOME", "/tmp"), ".local", "state") : Path.of(state);
        return new JsonlHistoryStore(base.resolve("devdesk").resolve("history").resolve(project.key()));
    }

    public Path directory() {
        return dir;
    }

    @Override
    public synchronized void append(HistoryEntry entry) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("at", entry.at().toString());
        line.put("service", entry.service());
        line.put("kind", entry.kind().name().toLowerCase());
        line.put("summary", entry.summary());
        line.put("data", entry.data());
        try {
            Files.createDirectories(dir);
            Files.writeString(
                    fileFor(entry.at()),
                    JSON.writeValueAsString(line) + "\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            if (!warned) { // say it once, loudly, but never break the running project over its history
                warned = true;
                System.err.println("devdesk: could not write history to " + dir + ": " + e.getMessage());
            }
        }
    }

    @Override
    public synchronized List<HistoryEntry> read(Instant since) {
        List<HistoryEntry> entries = new ArrayList<>();
        LocalDate first = LocalDate.ofInstant(since, ZoneOffset.UTC);
        for (Path file : days()) {
            if (LocalDate.parse(file.getFileName().toString().replace(".jsonl", ""))
                    .isBefore(first)) {
                continue;
            }
            try {
                for (String text : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    parse(text).filter(e -> !e.at().isBefore(since)).ifPresent(entries::add);
                }
            } catch (IOException e) {
                // a file deleted meanwhile: skip it
            }
        }
        entries.sort((a, b) -> a.at().compareTo(b.at()));
        return entries;
    }

    private static java.util.Optional<HistoryEntry> parse(String text) {
        try {
            Map<?, ?> m = JSON.readValue(text, Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) m.get("data");
            return java.util.Optional.of(new HistoryEntry(
                    Instant.parse((String) m.get("at")),
                    (String) m.get("service"),
                    HistoryEntry.Kind.valueOf(((String) m.get("kind")).toUpperCase()),
                    (String) m.get("summary"),
                    data == null ? Map.of() : data));
        } catch (RuntimeException e) {
            return java.util.Optional.empty(); // a half-written last line after a crash: skip it
        }
    }

    private Path fileFor(Instant at) {
        return dir.resolve(LocalDate.ofInstant(at, ZoneOffset.UTC) + ".jsonl");
    }

    private List<Path> days() {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().matches("\\d{4}-\\d{2}-\\d{2}\\.jsonl"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private void deleteOldDays() {
        LocalDate oldest = LocalDate.now(ZoneOffset.UTC).minusDays(KEEP_DAYS);
        for (Path file : days()) {
            if (LocalDate.parse(file.getFileName().toString().replace(".jsonl", ""))
                    .isBefore(oldest)) {
                try {
                    Files.delete(file);
                } catch (IOException e) {
                    // try again next time
                }
            }
        }
    }
}
