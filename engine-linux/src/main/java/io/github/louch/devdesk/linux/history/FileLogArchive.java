package io.github.louch.devdesk.linux.history;

import io.github.louch.devdesk.core.logs.LogArchive;
import io.github.louch.devdesk.core.logs.LogLine;
import io.github.louch.devdesk.core.logs.LogQuery;
import io.github.louch.devdesk.core.project.ProjectSpec;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keeps every line a project's services print, one JSON line each, in one file per day:
 * {@code $XDG_STATE_HOME/devdesk/logs/<project-key>/2026-10-13.jsonl}. Old days are deleted, and a day stops growing at
 * {@link #DAY_LIMIT}: a service printing in a loop must not fill the disk.
 */
public final class FileLogArchive implements LogArchive {

    static final int KEEP_DAYS = 7;
    static final long DAY_LIMIT = 50L * 1024 * 1024;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Path dir;
    /** For "which days are older than a week": injected, so tests don't depend on today's date. */
    private final Clock clock;
    // ---- guarded by "this" ----
    private LocalDate openDay;
    private BufferedWriter writer;
    private long written;
    private boolean full;

    public FileLogArchive(Path dir, Clock clock) {
        this.dir = dir;
        this.clock = clock;
        deleteOldDays();
    }

    public static FileLogArchive forProject(Map<String, String> env, ProjectSpec project, Clock clock) {
        String state = env.getOrDefault("XDG_STATE_HOME", "");
        Path base = state.isBlank() ? Path.of(env.getOrDefault("HOME", "/tmp"), ".local", "state") : Path.of(state);
        return new FileLogArchive(base.resolve("devdesk").resolve("logs").resolve(project.key()), clock);
    }

    @Override
    public synchronized void append(LogLine line) {
        LocalDate day = LocalDate.ofInstant(line.at(), ZoneOffset.UTC);
        try {
            if (!day.equals(openDay)) {
                rollTo(day);
            }
            if (full) {
                return;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("at", line.at().toString());
            m.put("service", line.service());
            m.put("stream", line.stream());
            m.put("text", line.text());
            String json = JSON.writeValueAsString(m) + "\n";
            if (written + json.length() > DAY_LIMIT) {
                // Said once, in the file itself, so a search shows why the day's lines end here.
                writer.write(JSON.writeValueAsString(Map.of(
                                "at",
                                line.at().toString(),
                                "service",
                                line.service(),
                                "stream",
                                "stderr",
                                "text",
                                "devdesk: today's log archive is full (" + DAY_LIMIT / 1024 / 1024
                                        + " MB): later lines of this day aren't kept for search"))
                        + "\n");
                writer.flush();
                full = true;
                return;
            }
            writer.write(json);
            writer.flush(); // a crash of DevDesk must not lose the lines that explain it
            written += json.length();
        } catch (IOException e) {
            // Search is a convenience: a full disk or a read-only folder must never stop the services.
            full = true;
        }
    }

    @Override
    public List<LogLine> search(LogQuery.Matcher matcher, Instant since, int limit) {
        List<LogLine> found = new ArrayList<>();
        LocalDate first = LocalDate.ofInstant(since, ZoneOffset.UTC);
        for (Path file : days()) {
            if (dayOf(file).map(d -> d.isBefore(first)).orElse(true)) {
                continue;
            }
            List<String> lines = read(file);
            for (int i = lines.size() - 1; i >= 0 && found.size() < limit; i--) {
                parse(lines.get(i)).filter(matcher::test).ifPresent(found::add);
            }
            if (found.size() >= limit) {
                break;
            }
        }
        return found;
    }

    @Override
    public List<LogLine> around(String service, Instant at, int before, int after) {
        Path file = dir.resolve(LocalDate.ofInstant(at, ZoneOffset.UTC) + ".jsonl");
        List<LogLine> lines = read(file).stream()
                .flatMap(l -> parse(l).stream())
                .filter(l -> l.service().equals(service))
                .toList();
        int index = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).at().isAfter(at)) {
                index = i; // the last line printed at or before the moment asked for
            }
        }
        return lines.subList(Math.max(0, index - before), Math.min(lines.size(), index + after + 1));
    }

    private void rollTo(LocalDate day) throws IOException {
        if (writer != null) {
            writer.close();
        }
        Files.createDirectories(dir);
        Path file = dir.resolve(day + ".jsonl");
        written = Files.exists(file) ? Files.size(file) : 0;
        full = written >= DAY_LIMIT;
        writer = Files.newBufferedWriter(
                file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        openDay = day;
        deleteOldDays();
    }

    /** Newest day first. */
    private List<Path> days() {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(f -> dayOf(f).isPresent())
                    .sorted((a, b) -> b.getFileName().compareTo(a.getFileName()))
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private void deleteOldDays() {
        LocalDate oldest = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).minusDays(KEEP_DAYS);
        for (Path file : days()) {
            if (dayOf(file).map(d -> d.isBefore(oldest)).orElse(false)) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException e) {
                    // tried again next time
                }
            }
        }
    }

    private static Optional<LocalDate> dayOf(Path file) {
        String name = file.getFileName().toString();
        try {
            return name.endsWith(".jsonl")
                    ? Optional.of(LocalDate.parse(name.substring(0, name.length() - ".jsonl".length())))
                    : Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static List<String> read(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : List.of();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static Optional<LogLine> parse(String json) {
        try {
            Map<?, ?> m = JSON.readValue(json, Map.class);
            return Optional.of(new LogLine(
                    Instant.parse(String.valueOf(m.get("at"))),
                    String.valueOf(m.get("service")),
                    String.valueOf(m.get("stream")),
                    String.valueOf(m.get("text"))));
        } catch (RuntimeException e) { // JacksonException is one too
            return Optional.empty(); // a line cut by a crash: skip it
        }
    }
}
