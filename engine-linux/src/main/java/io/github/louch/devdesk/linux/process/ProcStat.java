package io.github.louch.devdesk.linux.process;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

/**
 * The fields DevDesk needs from {@code /proc/<pid>/stat}, the kernel's live one-line summary of a process.
 *
 * @param state one letter (field 3): R running, S sleeping, Z zombie (dead, waiting for its parent to collect its exit
 *     code), ...
 * @param parentPid the process that started it (field 4)
 * @param processGroup the process group ID (field 5)
 * @param startTime when the process started, in clock ticks since boot (field 22). Together with the PID it identifies a
 *     process uniquely: a reused PID gets a new start time.
 */
public record ProcStat(long pid, char state, long parentPid, long processGroup, long startTime) {

    /** A zombie has already exited; only its exit code is waiting to be collected. It can't be signalled or stopped. */
    public boolean isZombie() {
        return state == 'Z';
    }

    /** Reads the stat line of a process, or empty if the process no longer exists. */
    public static Optional<ProcStat> read(long pid) {
        try {
            return Optional.of(parse(pid, Files.readString(Path.of("/proc", Long.toString(pid), "stat"))));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            // Also happens when the process exits while we read; treat it as gone.
            return Optional.empty();
        }
    }

    static ProcStat parse(long pid, String line) {
        // Field 2 is the program name in parentheses and may itself contain spaces or ')', e.g. "(my app)".
        // So split only after the LAST ')': what follows is field 3 onwards.
        String[] fields = line.substring(line.lastIndexOf(')') + 2).trim().split(" ");
        return new ProcStat(
                pid,
                fields[0].charAt(0),
                Long.parseLong(fields[4 - 3]),
                Long.parseLong(fields[5 - 3]),
                Long.parseLong(fields[22 - 3]));
    }

    /**
     * When it started, as a real time: the boot time ("btime" in /proc/stat, seconds since 1970) plus the start time
     * in ticks (100 per second, see ClockTicksTest).
     */
    public Optional<Instant> startedAt() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/stat"))) {
                if (line.startsWith("btime ")) {
                    long boot = Long.parseLong(line.substring(6).strip());
                    return Optional.of(Instant.ofEpochSecond(boot).plusMillis(startTime * 1000 / 100));
                }
            }
        } catch (IOException | RuntimeException e) {
            // fall through
        }
        return Optional.empty();
    }
}
