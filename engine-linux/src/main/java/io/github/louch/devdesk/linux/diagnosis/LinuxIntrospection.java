package io.github.louch.devdesk.linux.diagnosis;

import io.github.louch.devdesk.core.diagnosis.Introspection;
import io.github.louch.devdesk.core.diagnosis.ProcessInfo;
import io.github.louch.devdesk.core.network.Connection;
import io.github.louch.devdesk.core.network.Listener;
import io.github.louch.devdesk.linux.network.ProcNetListeners;
import io.github.louch.devdesk.linux.process.ProcStat;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/** Facts for diagnosis from /proc and the file system. Read-only: it never changes anything on the machine. */
public final class LinuxIntrospection implements Introspection {

    private static final long MAX_FILE_BYTES = 64 * 1024; // .env.example files are small; never read a huge file

    private final ProcNetListeners listeners;

    public LinuxIntrospection(ProcNetListeners listeners) {
        this.listeners = listeners;
    }

    @Override
    public Optional<Listener> listenerOn(int port) {
        return listeners.listenerOn(port);
    }

    @Override
    public List<Listener> listenersOf(Set<Long> pids) {
        return listeners.listenersOf(pids);
    }

    @Override
    public List<Connection> connectionsOf(Set<Long> pids) {
        return listeners.connectionsOf(pids);
    }

    @Override
    public Optional<ProcessInfo> process(long pid) {
        Optional<ProcStat> stat = ProcStat.read(pid);
        if (stat.isEmpty() || stat.get().isZombie()) {
            return Optional.empty();
        }
        String command;
        try {
            // Arguments are NUL-separated in /proc/<pid>/cmdline; kernel threads have none.
            command = Files.readString(Path.of("/proc", Long.toString(pid), "cmdline"))
                    .replace('\0', ' ')
                    .strip();
        } catch (IOException e) {
            return Optional.empty();
        }
        if (command.isEmpty()) {
            return Optional.empty();
        }
        Optional<Path> cwd;
        try {
            // /proc/<pid>/cwd is a link to the process's current folder; reading it is only allowed for our own
            // processes.
            cwd = Optional.of(Files.readSymbolicLink(Path.of("/proc", Long.toString(pid), "cwd")));
        } catch (IOException e) {
            cwd = Optional.empty();
        }
        return Optional.of(new ProcessInfo(
                pid, stat.get().parentPid(), command, cwd, stat.get().startedAt()));
    }

    @Override
    public List<ProcessInfo> processesIn(Path folder) {
        Path root = folder.toAbsolutePath().normalize();
        try (Stream<Path> entries = Files.list(Path.of("/proc"))) {
            return entries.map(p -> p.getFileName().toString())
                    .filter(name -> name.chars().allMatch(Character::isDigit))
                    .map(name -> process(Long.parseLong(name)))
                    .flatMap(Optional::stream)
                    .filter(p ->
                            p.workingDirectory().filter(d -> d.startsWith(root)).isPresent())
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Same search the shell does: each PATH folder in order, first executable file wins. */
    @Override
    public Optional<Path> findExecutable(String program, String path) {
        for (String folder : path.split(":")) {
            if (!folder.isBlank()) {
                Path candidate = Path.of(folder, program);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public FileStatus fileStatus(Path file) {
        if (!Files.exists(file)) {
            return FileStatus.MISSING;
        }
        return Files.isExecutable(file) ? FileStatus.EXECUTABLE : FileStatus.NOT_EXECUTABLE;
    }

    @Override
    public Optional<String> readFile(Path file) {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > MAX_FILE_BYTES) {
                return Optional.empty();
            }
            return Optional.of(Files.readString(file));
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
