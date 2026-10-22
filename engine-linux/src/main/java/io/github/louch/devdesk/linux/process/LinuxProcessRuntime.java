package io.github.louch.devdesk.linux.process;

import io.github.louch.devdesk.core.process.ExitStatus;
import io.github.louch.devdesk.core.process.LaunchException;
import io.github.louch.devdesk.core.process.OutputLine;
import io.github.louch.devdesk.core.process.ProcessLaunchSpec;
import io.github.louch.devdesk.core.process.ProcessRuntime;
import io.github.louch.devdesk.core.process.RunningProcess;
import io.github.louch.devdesk.core.process.Signal;
import io.github.louch.devdesk.core.process.StreamKind;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Runs processes on Linux so that DevDesk always owns the whole family a command starts (ADR 0002).
 *
 * <p>Each command runs as {@code [systemd-run --user --scope ...] setsid /bin/sh -c "<command>"}: the systemd scope
 * (a cgroup) holds every descendant, and {@code setsid} gives the command its own process group and session.
 */
public final class LinuxProcessRuntime implements ProcessRuntime {

    private static final long READY_TIMEOUT_MILLIS = 3_000;
    private static final long OUTPUT_SETTLE_MILLIS = 200;

    private final boolean useSystemdScope;
    private final Clock clock;
    private final RandomGenerator random;
    /** Group mode: where each group is written down, so a hard-killed DevDesk's groups are found again. */
    private final Optional<GroupLeftovers> records;

    private final Map<RunningProcess, Tracked> tracked = new ConcurrentHashMap<>();

    /** Where a process family lives, which decides how we find and signal its members. */
    private sealed interface Ownership {
        /** Mode A: a cgroup directory under /sys/fs/cgroup. */
        record Scope(Path cgroupDir) implements Ownership {}

        /**
         * Mode B: a process group. Members must have started at or after {@code notBefore} (clock ticks since boot),
         * so a recycled PID belonging to an older, unrelated process is never counted as ours.
         */
        record Group(long processGroup, long notBefore) implements Ownership {}

        /** The command finished before we saw its scope, and systemd already removed it: nothing is left to own. */
        record Nothing() implements Ownership {}
    }

    private record Tracked(String owner, Process process, Ownership ownership, CompletableFuture<ExitStatus> exit) {}

    /** Without records: for health-check commands and tests, whose processes are never left behind. */
    public LinuxProcessRuntime(boolean useSystemdScope, Clock clock, RandomGenerator random) {
        this(useSystemdScope, clock, random, Optional.empty());
    }

    public LinuxProcessRuntime(
            boolean useSystemdScope, Clock clock, RandomGenerator random, Optional<GroupLeftovers> records) {
        this.useSystemdScope = useSystemdScope;
        this.clock = clock;
        this.random = random;
        this.records = records;
    }

    /** Uses a systemd scope when a systemd user session is running, otherwise plain process groups. */
    public static LinuxProcessRuntime detect(Clock clock, RandomGenerator random) {
        return new LinuxProcessRuntime(systemdUserSessionRunning(), clock, random);
    }

    /** Like {@link #detect(Clock, RandomGenerator)}, and in group mode writes each group down in {@code records}. */
    public static LinuxProcessRuntime detect(Clock clock, RandomGenerator random, GroupLeftovers records) {
        return new LinuxProcessRuntime(systemdUserSessionRunning(), clock, random, Optional.of(records));
    }

    public boolean usesSystemdScope() {
        return useSystemdScope;
    }

    @Override
    public RunningProcess start(ProcessLaunchSpec spec, Consumer<OutputLine> output) throws LaunchException {
        List<String> command = new ArrayList<>();
        String unit = useSystemdScope ? unitName(spec.owner(), spec.name()) : null;
        if (useSystemdScope) {
            // --collect: systemd removes the scope even if the command fails, so no stale units pile up.
            command.addAll(List.of("systemd-run", "--user", "--scope", "--quiet", "--collect", "--unit=" + unit, "--"));
        }
        command.addAll(List.of("setsid", "/bin/sh", "-c", spec.command()));

        ProcessBuilder builder =
                new ProcessBuilder(command).directory(spec.workingDirectory().toFile());
        Map<String, String> env = builder.environment();
        env.clear(); // the spec's environment is complete; don't leak DevDesk's own variables into services
        env.putAll(spec.environment());
        // Group mode: a random marker every process of this family inherits. After a crash, it's what proves a
        // process in a recorded group is really ours, even if the kernel reused the group's number meanwhile.
        String marker = records.isPresent() && !useSystemdScope && !spec.owner().isBlank() ? newMarker() : "";
        if (!marker.isEmpty()) {
            env.put(GroupLeftovers.MARKER, marker);
        }
        if (useSystemdScope) {
            // systemd-run needs these to reach the user's systemd manager over D-Bus.
            copyIfMissing(env, "XDG_RUNTIME_DIR");
            copyIfMissing(env, "DBUS_SESSION_BUS_ADDRESS");
        }

        long notBefore = ticksSinceBoot();
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new LaunchException(
                    "Could not start '" + spec.name() + "' in " + spec.workingDirectory() + ": " + e.getMessage(), e);
        }
        closeQuietly(process);
        // Drain both pipes from the first moment; virtual threads are cheap enough for two per process.
        CompletableFuture<Void> stdoutDone =
                drain(spec.name(), new LineReader(process.getInputStream(), StreamKind.STDOUT, output));
        CompletableFuture<Void> stderrDone =
                drain(spec.name(), new LineReader(process.getErrorStream(), StreamKind.STDERR, output));
        CompletableFuture<Void> outputDone = CompletableFuture.allOf(stdoutDone, stderrDone);

        Ownership ownership = awaitOwnership(spec, process, unit, notBefore);
        if (ownership instanceof Ownership.Group g) {
            records.ifPresent(r ->
                    r.remember(spec.owner(), spec.name(), g.processGroup(), g.notBefore(), marker, spec.command()));
        }
        RunningProcess running = new RunningProcess(spec.name(), process.pid(), clock.instant());
        // Report the exit only after the last output lines arrived, so "exited" never prints before them.
        // Bounded: a leftover child can hold the pipe open forever, and then we report the exit anyway.
        CompletableFuture<ExitStatus> exit = process.onExit()
                .thenCompose(p -> outputDone
                        .completeOnTimeout(null, OUTPUT_SETTLE_MILLIS, TimeUnit.MILLISECONDS)
                        .thenApply(ignored -> ExitStatus.of(p.exitValue())));
        tracked.put(running, new Tracked(spec.owner(), process, ownership, exit));
        // Exited with no children left: its record has nothing to find any more.
        exit.thenRun(() -> {
            if (ownership instanceof Ownership.Group g) {
                forgetIfEmpty(spec.owner(), g);
            }
        });
        return running;
    }

    @Override
    public void signal(RunningProcess process, Signal signal) {
        switch (find(process).ownership()) {
            case Ownership.Scope scope -> {
                if (signal == Signal.KILL && killWholeCgroup(scope.cgroupDir())) {
                    return;
                }
                cgroupMembers(scope.cgroupDir()).forEach(pid -> send(pid, signal));
            }
            case Ownership.Group group -> {
                if (!groupMembers(group).isEmpty()) {
                    // Negative PID = every process in that group (kill(2)).
                    send(-group.processGroup(), signal);
                }
            }
            case Ownership.Nothing nothing -> {
                // Nothing left alive to signal.
            }
        }
    }

    @Override
    public CompletableFuture<ExitStatus> onExit(RunningProcess process) {
        return find(process).exit();
    }

    @Override
    public Set<Long> members(RunningProcess process) {
        return switch (find(process).ownership()) {
            case Ownership.Scope scope -> cgroupMembers(scope.cgroupDir());
            case Ownership.Group group -> forgetIfEmpty(find(process).owner(), group);
            case Ownership.Nothing nothing -> Set.of();
        };
    }

    private String newMarker() {
        byte[] bytes = new byte[12];
        random.nextBytes(bytes);
        return java.util.HexFormat.of().formatHex(bytes);
    }

    /** The group's members; once there are none, its record is deleted (nothing left to find after a crash). */
    private Set<Long> forgetIfEmpty(String owner, Ownership.Group group) {
        Set<Long> members = groupMembers(group);
        if (members.isEmpty()) {
            records.ifPresent(r -> r.forget(owner, group.processGroup()));
        }
        return members;
    }

    private Tracked find(RunningProcess process) {
        Tracked t = tracked.get(process);
        if (t == null) {
            throw new IllegalArgumentException("'" + process.name() + "' (PID " + process.pid()
                    + ") was not started by this runtime; refusing to touch it");
        }
        return t;
    }

    /**
     * Waits until setsid has run (process group ID == PID) and, in scope mode, until the process is inside its scope.
     * Signalling earlier could miss part of the family.
     */
    private Ownership awaitOwnership(ProcessLaunchSpec spec, Process process, String unit, long notBefore)
            throws LaunchException {
        long pid = process.pid();
        Optional<Path> scopeDir = Optional.empty();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(READY_TIMEOUT_MILLIS);
        while (System.nanoTime() < deadline) {
            if (unit != null && scopeDir.isEmpty()) {
                scopeDir = scopeDirOf(pid, unit);
            }
            boolean ownGroup =
                    ProcStat.read(pid).map(s -> s.processGroup() == pid).orElse(false);
            boolean ready = ownGroup && (unit == null || scopeDir.isPresent());
            // A command can finish before we look (`echo hi`, exit 127) and still leave children running
            // (`sleep 1000 & exit`). So ownership must never depend on the top process still being alive.
            if (ready || !process.isAlive()) {
                if (unit == null) {
                    return new Ownership.Group(pid, notBefore);
                }
                return scopeDir.or(() -> askSystemdForScope(unit))
                        .<Ownership>map(Ownership.Scope::new)
                        .orElseGet(Ownership.Nothing::new);
            }
            LockSupport.parkNanos(1_000_000); // 1 ms between checks; setsid usually takes well under that
        }
        process.destroyForcibly();
        throw new LaunchException(
                "'" + spec.name() + "' did not get its own process group within " + READY_TIMEOUT_MILLIS
                        + " ms (is systemd-run hanging?)",
                null);
    }

    /** The scope directory, once {@code /proc/<pid>/cgroup} (one line: "0::/user.slice/...") shows our unit. */
    private static Optional<Path> scopeDirOf(long pid, String unit) {
        try {
            String line = Files.readString(Path.of("/proc", Long.toString(pid), "cgroup"))
                    .trim();
            return line.endsWith("/" + unit + ".scope")
                    ? Optional.of(Path.of("/sys/fs/cgroup" + line.substring(line.indexOf("::") + 2)))
                    : Optional.empty();
        } catch (IOException e) {
            return Optional.empty(); // process already gone
        }
    }

    /** Fallback when the top process exited before we saw its scope: systemd still knows the scope's cgroup. */
    private static Optional<Path> askSystemdForScope(String unit) {
        try {
            Process show = new ProcessBuilder(
                            "systemctl", "--user", "show", "--property=ControlGroup", "--value", unit + ".scope")
                    .start();
            String cgroup = new String(show.getInputStream().readAllBytes()).trim();
            show.waitFor(2, TimeUnit.SECONDS);
            return cgroup.isEmpty() ? Optional.empty() : Optional.of(Path.of("/sys/fs/cgroup" + cgroup));
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /**
     * Current time in kernel clock ticks since boot, the unit of the start-time field in {@code /proc/<pid>/stat}.
     * USER_HZ is 100 on Linux (checked by a test with {@code getconf CLK_TCK}). One tick is subtracted because
     * /proc/uptime is rounded to 10 ms.
     */
    static long ticksSinceBoot() {
        try {
            String uptime = Files.readString(Path.of("/proc/uptime")).split(" ")[0];
            return (long) (Double.parseDouble(uptime) * 100) - 1;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Set<Long> cgroupMembers(Path cgroupDir) {
        try (Stream<String> lines = Files.lines(cgroupDir.resolve("cgroup.procs"))) {
            return lines.map(Long::parseLong).collect(Collectors.toUnmodifiableSet());
        } catch (IOException | UncheckedIOException e) {
            if (cgroupIsGone(e)) {
                return Set.of(); // systemd removed the scope: everything in it has exited
            }
            throw e instanceof UncheckedIOException u ? u : new UncheckedIOException((IOException) e);
        }
    }

    /** Writing "1" to cgroup.kill makes the kernel SIGKILL every process in the cgroup at once (kernel 5.14+). */
    private static boolean killWholeCgroup(Path cgroupDir) {
        try {
            Files.writeString(cgroupDir.resolve("cgroup.kill"), "1");
            return true;
        } catch (IOException e) {
            // Gone = nothing left to kill. Otherwise (older kernel without cgroup.kill) signal each PID instead.
            return cgroupIsGone(e);
        }
    }

    /**
     * A cgroup being removed answers reads with ENODEV ("No such device"), not "file not found". Seen in testing:
     * reading cgroup.procs right as systemd deletes the scope.
     */
    private static boolean cgroupIsGone(Exception e) {
        Throwable cause = e instanceof UncheckedIOException u ? u.getCause() : e;
        // ENODEV: the scope directory still shows but the kernel already tore the cgroup down. It surfaces either when
        // opening (FileSystemException, errno text in getReason()) or mid-read (plain IOException).
        String reason = cause instanceof FileSystemException f ? f.getReason() : cause.getMessage();
        return cause instanceof NoSuchFileException || "No such device".equals(reason);
    }

    /**
     * Every live process in our group that started after we launched it. The start-time check skips an older,
     * unrelated process in case the kernel recycled the number (PID reuse).
     */
    private static Set<Long> groupMembers(Ownership.Group group) {
        try (Stream<Path> entries = Files.list(Path.of("/proc"))) {
            return entries.map(p -> p.getFileName().toString())
                    .filter(name -> name.chars().allMatch(Character::isDigit))
                    .flatMap(name -> ProcStat.read(Long.parseLong(name)).stream())
                    // Zombies are already dead (cgroup.procs leaves them out too); counting them would wait forever.
                    .filter(s -> !s.isZombie())
                    .filter(s -> s.processGroup() == group.processGroup() && s.startTime() >= group.notBefore())
                    .map(ProcStat::pid)
                    .collect(Collectors.toUnmodifiableSet());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void send(long pid, Signal signal) {
        int errno = LibC.kill(pid, number(signal));
        if (errno == 0 || errno == LibC.ESRCH) {
            return; // ESRCH: it exited between listing and signalling, which is what we wanted anyway
        }
        if (errno == LibC.EPERM) {
            throw new IllegalStateException("Not allowed to signal PID " + pid + ": it belongs to someone else. "
                    + "DevDesk must never signal processes it didn't start; this is a bug, please report it");
        }
        throw new IllegalStateException("kill(" + pid + ", " + signal + ") failed with errno " + errno);
    }

    /** Linux signal numbers (see `kill -l`). */
    private static int number(Signal signal) {
        return switch (signal) {
            case INT -> 2;
            case KILL -> 9;
            case TERM -> 15;
        };
    }

    /**
     * {@code devdesk-<project key>--<service>-<8 hex>}, or {@code devdesk-<service>-<8 hex>} without a project. The
     * project key in the name lets DevDesk recognise its own leftover scopes after a hard kill. Unit names allow only
     * a few characters, so anything else becomes '_'.
     */
    static String unitName(String owner, String serviceName, int random) {
        String safe = serviceName.replaceAll("[^A-Za-z0-9_-]", "_");
        String prefix = owner.isEmpty() ? "devdesk-" : "devdesk-" + owner.replaceAll("[^A-Za-z0-9_.-]", "_") + "--";
        // 8 random hex digits: systemd refuses a second unit with the same name while the first still exists.
        return prefix + safe + "-" + String.format("%08x", random);
    }

    private String unitName(String owner, String serviceName) {
        return unitName(owner, serviceName, random.nextInt());
    }

    private static CompletableFuture<Void> drain(String name, LineReader reader) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        Thread.ofVirtual().name("output-" + name).start(() -> {
            reader.run();
            done.complete(null);
        });
        return done;
    }

    private static void copyIfMissing(Map<String, String> env, String key) {
        String value = System.getenv(key);
        if (value != null) {
            env.putIfAbsent(key, value);
        }
    }

    /** Services get no stdin: one that reads it sees end-of-input instead of waiting forever. */
    private static void closeQuietly(Process process) {
        try {
            process.getOutputStream().close();
        } catch (IOException e) {
            // The process already exited; there is nothing to close.
        }
    }

    private static boolean systemdUserSessionRunning() {
        try {
            Process check = new ProcessBuilder("systemctl", "--user", "is-system-running")
                    .redirectErrorStream(true)
                    .start();
            String state = new String(check.getInputStream().readAllBytes()).trim();
            check.waitFor(2, TimeUnit.SECONDS);
            // "degraded" = running but some unrelated unit failed; scopes still work.
            return state.equals("running") || state.equals("degraded");
        } catch (IOException e) {
            return false; // no systemctl at all
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
