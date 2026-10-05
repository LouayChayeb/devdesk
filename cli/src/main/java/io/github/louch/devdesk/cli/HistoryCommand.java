package io.github.louch.devdesk.cli;

import io.github.louch.devdesk.core.history.HistoryEntry;
import io.github.louch.devdesk.core.history.Timeline;
import io.github.louch.devdesk.linux.history.JsonlHistoryStore;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** {@code devdesk history}: what happened to this project while you weren't looking, from the flight recorder. */
@Command(
        name = "history",
        description = "Show what happened to the project: crashes, restarts, health, findings, memory.")
final class HistoryCommand implements Callable<Integer> {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("MMM dd HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final Pattern SINCE = Pattern.compile("(\\d+)([mhd])");

    @Option(
            names = {"-f", "--file"},
            defaultValue = "devdesk.yaml",
            description = "The project file.")
    Path file;

    @Option(
            names = "--since",
            defaultValue = "24h",
            description = "How far back: 30m, 6h, 2d (default: ${DEFAULT-VALUE}).")
    String since;

    @Option(names = "--service", description = "Only this service.")
    Optional<String> service;

    @Option(names = "--all", description = "Also show routine states (waiting, stopping).")
    boolean all;

    private final Map<String, String> env;
    private final Clock clock;
    private final PrintStream out;
    private final PrintStream err;

    HistoryCommand(Map<String, String> env, Clock clock, PrintStream out, PrintStream err) {
        this.env = env;
        this.clock = clock;
        this.out = out;
        this.err = err;
    }

    @Override
    public Integer call() {
        Optional<ProjectFile> loaded = ProjectFile.load(file, env, err);
        if (loaded.isEmpty()) {
            return ProjectFile.INVALID;
        }
        Matcher m = SINCE.matcher(since);
        if (!m.matches()) {
            err.println("devdesk: --since must look like 30m, 6h or 2d");
            return ProjectFile.INVALID;
        }
        long n = Long.parseLong(m.group(1));
        Duration back = switch (m.group(2)) {
            case "m" -> Duration.ofMinutes(n);
            case "h" -> Duration.ofHours(n);
            default -> Duration.ofDays(n);
        };
        var store = JsonlHistoryStore.forProject(env, loaded.get().project());
        List<HistoryEntry> entries = store.read(clock.instant().minus(back)).stream()
                .filter(e -> service.isEmpty()
                        || e.service().equals(service.get())
                        || e.service().isEmpty())
                .toList();
        out.println(
                "History of " + loaded.get().project().name() + ", last " + since + "  (" + store.directory() + ")");
        if (entries.isEmpty()) {
            out.println("Nothing recorded yet. DevDesk records while `devdesk up` or the app runs the project.");
            return 0;
        }
        int width = loaded.get().project().services().keySet().stream()
                .mapToInt(String::length)
                .max()
                .orElse(4);
        for (Timeline.Item item : Timeline.of(entries)) {
            HistoryEntry e = item.entry();
            switch (e.kind()) {
                case SESSION -> {
                    if (item.afterUncleanStop()) {
                        out.println(
                                " ".repeat(15) + "   !! no clean stop was recorded before this: DevDesk was killed or "
                                        + "crashed, and its services may have kept running");
                    }
                    out.println(TIME.format(e.at()) + "  ── " + e.summary());
                }
                case STATE -> {
                    if (all || !item.routine()) {
                        out.println(TIME.format(e.at()) + "  " + pad(e.service(), width) + "  "
                                + symbol(String.valueOf(e.data().get("to"))) + " " + e.summary());
                    }
                }
                case FINDING -> out.println(" ".repeat(15) + "  " + pad("", width) + "    ↳ " + e.summary());
                case WARNING ->
                    out.println(TIME.format(e.at()) + "  " + pad(e.service(), width) + "  ! " + e.summary());
                case RESOURCES -> {} // summarised below
            }
        }
        resources(entries, width);
        return 0;
    }

    /** One line per service: how memory moved and the CPU peak, instead of hundreds of samples. */
    private void resources(List<HistoryEntry> entries, int width) {
        Map<String, List<HistoryEntry>> perService = new LinkedHashMap<>();
        entries.stream()
                .filter(e -> e.kind() == HistoryEntry.Kind.RESOURCES)
                .forEach(e -> perService
                        .computeIfAbsent(e.service(), k -> new java.util.ArrayList<>())
                        .add(e));
        if (perService.isEmpty()) {
            return;
        }
        out.println();
        out.println("Resources:");
        perService.forEach((name, samples) -> {
            HistoryEntry peak = samples.stream()
                    .max((a, b) -> Long.compare(memory(a), memory(b)))
                    .orElseThrow();
            double cpuPeak = samples.stream()
                    .mapToDouble(s -> ((Number) s.data().get("cpu")).doubleValue())
                    .max()
                    .orElse(0);
            out.println("  " + pad(name, width) + "  memory " + mb(memory(samples.getFirst())) + " → peak "
                    + mb(memory(peak))
                    + " at " + TIME.format(peak.at()).substring(7) + " → last " + mb(memory(samples.getLast()))
                    + "   CPU peak " + Math.round(cpuPeak) + "%   (" + samples.size() + " samples)");
        });
    }

    private static long memory(HistoryEntry e) {
        return ((Number) e.data().getOrDefault("memory", 0)).longValue();
    }

    private static String mb(long bytes) {
        double mb = bytes / (1024.0 * 1024);
        return mb >= 1024 ? String.format("%.1f GB", mb / 1024) : Math.round(mb) + " MB";
    }

    private static String symbol(String state) {
        return switch (state) {
            case "healthy", "completed" -> "✓";
            case "running" -> "▶";
            case "starting", "waiting" -> "…";
            case "stopped", "stopping" -> "■";
            default -> "✗";
        };
    }

    private static String pad(String s, int width) {
        return s + " ".repeat(Math.max(0, width - s.length()));
    }
}
