package io.github.louch.devdesk.linux.process;

import io.github.louch.devdesk.core.leftovers.Leftover;
import io.github.louch.devdesk.core.leftovers.LeftoverFinder;
import io.github.louch.devdesk.core.project.ProjectSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Finds systemd scopes a previous DevDesk created for a project and never stopped. The proof of ownership is the
 * scope's name, {@code devdesk-<project key>--<service>-<hex>}, which only DevDesk creates. Process groups (no
 * systemd) can't be found again after a hard kill, so without systemd this finds nothing.
 */
public final class ScopeLeftovers implements LeftoverFinder {

    private static final long GRACE_MILLIS = 10_000;

    @Override
    public List<Leftover> find(ProjectSpec project) {
        String prefix = "devdesk-" + project.key() + "--";
        List<Leftover> found = new ArrayList<>();
        for (String unit : run(
                "systemctl", "--user", "list-units", "--all", "--plain", "--no-legend", "--type=scope", prefix + "*")) {
            String name = unit.split("\\s+")[0];
            if (!name.startsWith(prefix) || !name.endsWith(".scope")) {
                continue;
            }
            Path cgroup = cgroupOf(name);
            Set<Long> pids = members(cgroup);
            if (pids.isEmpty()) {
                continue; // already dying: systemd removes empty scopes by itself
            }
            String service = name.substring(prefix.length(), name.lastIndexOf('-'));
            found.add(new Leftover(service, Leftover.Kind.PROCESS, name, pids, describe(pids), startedAt(pids)));
        }
        return found;
    }

    @Override
    public String remove(Leftover leftover) {
        Path cgroup = cgroupOf(leftover.id());
        // SIGTERM first so the program can shut down cleanly; the kernel's cgroup.kill only if it doesn't.
        members(cgroup).forEach(pid -> LibC.kill(pid, 15));
        long deadline = System.currentTimeMillis() + GRACE_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (members(cgroup).isEmpty()) {
                return "stopped";
            }
            sleep(100);
        }
        try {
            Files.writeString(cgroup.resolve("cgroup.kill"), "1"); // SIGKILL to everything in the box at once
        } catch (IOException e) {
            return members(cgroup).isEmpty() ? "stopped" : "could not stop it: " + e.getMessage();
        }
        sleep(500);
        return members(cgroup).isEmpty()
                ? "force-killed: it ignored SIGTERM for 10 s"
                : "still running: " + members(cgroup);
    }

    private static Path cgroupOf(String unit) {
        List<String> out = run("systemctl", "--user", "show", "--property=ControlGroup", "--value", unit);
        return Path.of(
                "/sys/fs/cgroup" + (out.isEmpty() ? "/missing" : out.getFirst().strip()));
    }

    private static Set<Long> members(Path cgroup) {
        try (Stream<String> lines = Files.lines(cgroup.resolve("cgroup.procs"))) {
            return lines.map(Long::parseLong).collect(Collectors.toUnmodifiableSet());
        } catch (IOException | java.io.UncheckedIOException e) {
            return Set.of();
        }
    }

    /** The real program, not its sh / setsid wrapper: "python3 api.py" says more than "/bin/sh -c ...". */
    private static String describe(Set<Long> pids) {
        List<String> commands = pids.stream()
                .sorted()
                .map(ScopeLeftovers::commandLine)
                .flatMap(Optional::stream)
                .toList();
        return commands.stream()
                .filter(c -> !c.startsWith("/bin/sh") && !c.startsWith("sh ") && !c.startsWith("setsid"))
                .findFirst()
                .orElse(commands.isEmpty() ? "?" : commands.getFirst());
    }

    private static Optional<String> commandLine(long pid) {
        try {
            String c = Files.readString(Path.of("/proc", Long.toString(pid), "cmdline"))
                    .replace('\0', ' ')
                    .strip();
            return c.isEmpty() ? Optional.empty() : Optional.of(c);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Start time of the oldest member. */
    private static Optional<Instant> startedAt(Set<Long> pids) {
        return pids.stream()
                .flatMap(p -> ProcStat.read(p).stream())
                .min(java.util.Comparator.comparingLong(ProcStat::startTime))
                .flatMap(ProcStat::startedAt);
    }

    private static List<String> run(String... command) {
        try {
            Process p = new ProcessBuilder(command)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            List<String> lines = new String(p.getInputStream().readAllBytes())
                    .lines()
                    .filter(l -> !l.isBlank())
                    .toList();
            p.waitFor();
            return lines;
        } catch (IOException e) {
            return List.of(); // no systemctl: no scopes to find
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
