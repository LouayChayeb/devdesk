package io.github.louch.devdesk.core.diagnosis;

import io.github.louch.devdesk.core.network.Connection;
import io.github.louch.devdesk.core.network.Listener;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** A pretend machine for rule tests: whatever ports, programs and files each test puts in. */
final class FakeIntrospection implements Introspection {

    final Map<Integer, Listener> listeners = new HashMap<>();
    final Map<String, Path> programs = new HashMap<>();
    final Map<Path, String> files = new HashMap<>();
    final Map<Path, FileStatus> fileStatuses = new HashMap<>();
    final Map<Long, ProcessInfo> processes = new HashMap<>();
    final List<Connection> connections = new ArrayList<>();

    @Override
    public List<Listener> allListeners() {
        return List.copyOf(listeners.values());
    }

    @Override
    public Optional<String> devdeskProjectOf(long pid) {
        return Optional.empty();
    }

    @Override
    public List<Connection> connectionsOf(Set<Long> pids) {
        return connections.stream().filter(c -> pids.contains(c.pid())).toList();
    }

    @Override
    public Optional<ProcessInfo> process(long pid) {
        return Optional.ofNullable(processes.get(pid));
    }

    @Override
    public List<ProcessInfo> processesIn(Path folder) {
        return processes.values().stream()
                .filter(p ->
                        p.workingDirectory().filter(d -> d.startsWith(folder)).isPresent())
                .toList();
    }

    @Override
    public Optional<Listener> listenerOn(int port) {
        return Optional.ofNullable(listeners.get(port));
    }

    @Override
    public List<Listener> listenersOf(Set<Long> pids) {
        return listeners.values().stream()
                .filter(l -> l.pid().filter(pids::contains).isPresent())
                .toList();
    }

    @Override
    public Optional<Path> findExecutable(String program, String path) {
        return Optional.ofNullable(programs.get(program));
    }

    @Override
    public FileStatus fileStatus(Path file) {
        return fileStatuses.getOrDefault(file, FileStatus.MISSING);
    }

    @Override
    public Optional<String> readFile(Path file) {
        return Optional.ofNullable(files.get(file));
    }
}
