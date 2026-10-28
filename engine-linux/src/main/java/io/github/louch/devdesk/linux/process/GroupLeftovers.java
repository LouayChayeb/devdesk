package io.github.louch.devdesk.linux.process;

import io.github.louch.devdesk.core.leftovers.Leftover;
import io.github.louch.devdesk.core.leftovers.LeftoverFinder;
import io.github.louch.devdesk.core.project.ProjectSpec;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Leftovers in process-group mode (no systemd). A process family can't be found again after a hard kill unless
 * someone wrote it down, so the runtime writes one small record per service it starts
 * ({@code <state>/groups/<project key>/<marker>.group}) <em>before</em> starting it, and deletes it once the family is
 * gone. On the next start, a record still there names a family a dead DevDesk left behind.
 *
 * <p>Ownership proof (CLAUDE.md principle 2): every process of the family carries the record's random marker in its
 * environment ({@value #MARKER}, inherited by every child, also one that left the process group with setsid). A
 * process counts only if it carries that exact marker, started after the record's {@code notBefore}, on the same boot
 * (boot id). Group numbers and start times alone aren't enough: once a group dies out, the kernel may give its number
 * to an unrelated program. A process whose environment can't be read (another user's) never counts.
 */
public final class GroupLeftovers implements LeftoverFinder {

    private static final long GRACE_MILLIS = 10_000;

    /** The environment variable holding a group's marker. */
    public static final String MARKER = "DEVDESK_GROUP";

    private final Path folder;

    public GroupLeftovers(Path folder) {
        this.folder = folder;
    }

    /** {@code $XDG_STATE_HOME/devdesk/groups}, next to DevDesk's other state. */
    public static Path defaultFolder(Map<String, String> env) {
        String state = env.getOrDefault("XDG_STATE_HOME", "");
        Path base = state.isBlank() ? Path.of(env.getOrDefault("HOME", "/tmp"), ".local", "state") : Path.of(state);
        return base.resolve("devdesk").resolve("groups");
    }

    /**
     * Written <em>before</em> the process is started: a DevDesk killed at any moment after the fork leaves a record,
     * so nothing it started can go unnoticed.
     */
    void remember(String owner, String service, long notBefore, String marker, String command) {
        if (owner.isBlank() || marker.isBlank()) {
            return; // not a project's service (devdesk run, a health check): nothing to recover later
        }
        Properties p = new Properties();
        p.setProperty("service", service);
        p.setProperty("notBefore", Long.toString(notBefore));
        p.setProperty("boot", bootId());
        p.setProperty("marker", marker);
        p.setProperty("command", command);
        try {
            Path dir = Files.createDirectories(folder.resolve(owner));
            Path tmp = dir.resolve(marker + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                p.store(w, "a process family DevDesk started; deleted when it's gone");
            }
            // rename(2) is atomic: a kill mid-write never leaves half a record.
            Files.move(tmp, dir.resolve(marker + ".group"), StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // Not fatal: without the record, only a hard kill of DevDesk would go unnoticed.
        }
    }

    /** The family is gone: nothing to find later. */
    void forget(String owner, String marker) {
        if (owner.isBlank() || marker.isBlank()) {
            return;
        }
        try {
            Files.deleteIfExists(folder.resolve(owner).resolve(marker + ".group"));
        } catch (IOException e) {
            // found again next time, proven empty, and deleted then
        }
    }

    @Override
    public List<Leftover> find(ProjectSpec project) {
        List<Leftover> found = new ArrayList<>();
        for (Record r : records(project.key())) {
            Set<Long> pids = members(r);
            if (pids.isEmpty()) {
                forget(project.key(), r.marker());
                continue;
            }
            found.add(new Leftover(
                    r.service(),
                    Leftover.Kind.PROCESS,
                    id(r),
                    pids,
                    r.command(),
                    pids.stream()
                            .flatMap(pid -> ProcStat.read(pid).stream())
                            .min(Comparator.comparingLong(ProcStat::startTime))
                            .flatMap(ProcStat::startedAt)));
        }
        return found;
    }

    @Override
    public String remove(Leftover leftover) {
        String owner = leftover.id().substring(0, Math.max(0, leftover.id().lastIndexOf('/')));
        Optional<Record> record =
                records(owner).stream().filter(r -> leftover.id().equals(id(r))).findFirst();
        if (record.isEmpty()) {
            return "stopped"; // its record is gone: it was proven empty meanwhile
        }
        Record r = record.get();
        // SIGTERM first so the program can shut down cleanly. Each signal goes to a PID proven ours a moment ago,
        // never to a group id: by now that id could in theory name someone else's group.
        members(r).forEach(pid -> LibC.kill(pid, 15));
        long deadline = System.currentTimeMillis() + GRACE_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (members(r).isEmpty()) {
                forget(r.owner(), r.marker());
                return "stopped";
            }
            sleep(100);
        }
        members(r).forEach(pid -> LibC.kill(pid, 9));
        sleep(500);
        Set<Long> still = members(r);
        if (still.isEmpty()) {
            forget(r.owner(), r.marker());
            return "force-killed: it ignored SIGTERM for 10 s";
        }
        return "still running: " + still;
    }

    private record Record(String owner, String service, long notBefore, String boot, String marker, String command) {}

    /** The live processes that provably belong to this record's family. */
    private static Set<Long> members(Record r) {
        if (!r.boot().equals(bootId())) {
            return Set.of(); // the machine rebooted: every process from then is gone
        }
        return carriers(r.marker(), r.notBefore());
    }

    /** Live processes started at or after {@code notBefore} (ticks since boot) whose environment holds the marker. */
    static Set<Long> carriers(String marker, long notBefore) {
        if (marker.isBlank()) {
            return Set.of();
        }
        try (Stream<Path> entries = Files.list(Path.of("/proc"))) {
            return entries.map(p -> p.getFileName().toString())
                    .filter(name -> name.chars().allMatch(Character::isDigit))
                    .flatMap(name -> ProcStat.read(Long.parseLong(name)).stream())
                    .filter(s -> !s.isZombie() && s.startTime() >= notBefore)
                    .map(ProcStat::pid)
                    .filter(pid -> carries(pid, marker))
                    .collect(Collectors.toUnmodifiableSet());
        } catch (IOException e) {
            return Set.of();
        }
    }

    private List<Record> records(String owner) {
        Path dir = folder.resolve(owner);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<Record> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(f -> f.toString().endsWith(".group")).toList()) {
                Properties p = new Properties();
                try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                    p.load(r);
                    out.add(new Record(
                            owner,
                            p.getProperty("service", "?"),
                            Long.parseLong(p.getProperty("notBefore")),
                            p.getProperty("boot", ""),
                            p.getProperty("marker", ""),
                            p.getProperty("command", "?")));
                } catch (IOException | RuntimeException e) {
                    // unreadable record: nothing provable in it, so nothing is touched
                }
            }
        } catch (IOException e) {
            return List.of();
        }
        return out;
    }

    /** "<project key>/<marker>": the record it came from, so remove() finds it again. */
    private static String id(Record r) {
        return r.owner() + "/" + r.marker();
    }

    /** Does the process's environment hold {@code DEVDESK_GROUP=<marker>}? /proc/<pid>/environ is NUL-separated. */
    private static boolean carries(long pid, String marker) {
        if (marker.isBlank()) {
            return false; // a record without a marker proves nothing
        }
        try {
            Path file = Path.of("/proc", Long.toString(pid), "environ");
            byte[] environ = Files.readAllBytes(file);
            // While a process is in execve, the kernel shows no environment until the new program's is set up
            // (measured: 287 of 300 freshly forked children read empty first). Look again briefly before deciding.
            for (int i = 0; environ.length == 0 && i < 20; i++) {
                java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
                environ = Files.readAllBytes(file);
            }
            String wanted = MARKER + "=" + marker;
            for (String entry : new String(environ, StandardCharsets.UTF_8).split("\0")) {
                if (entry.equals(wanted)) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            return false; // exited, or not ours to read (another user): never claimed
        }
    }

    /** Changes at every boot: a record from before a reboot names processes that are all gone. */
    static String bootId() {
        try {
            return Files.readString(Path.of("/proc/sys/kernel/random/boot_id")).strip();
        } catch (IOException e) {
            return "unknown";
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
