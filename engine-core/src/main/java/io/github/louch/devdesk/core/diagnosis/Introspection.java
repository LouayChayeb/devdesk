package io.github.louch.devdesk.core.diagnosis;

import io.github.louch.devdesk.core.network.Connection;
import io.github.louch.devdesk.core.network.Listener;
import io.github.louch.devdesk.core.network.ListenerLookup;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Questions rules may ask the machine. Implemented with /proc and the file system on Linux; faked in tests. */
public interface Introspection extends ListenerLookup {

    /** Every TCP port the given processes listen on. */
    List<Listener> listenersOf(Set<Long> pids);

    /** Every open TCP connection the given processes hold (both directions). */
    List<Connection> connectionsOf(Set<Long> pids);

    /** Where {@code program} is found on {@code path} (a PATH value like "/usr/bin:/bin"), if anywhere. */
    Optional<Path> findExecutable(String program, String path);

    /** One process, if it exists (and as much as we may see of it). */
    Optional<ProcessInfo> process(long pid);

    /** Your processes whose current folder is {@code folder} or inside it. */
    List<ProcessInfo> processesIn(Path folder);

    /** Whether a file exists and may be run as a program. */
    FileStatus fileStatus(Path file);

    /** What {@link #fileStatus} found. */
    enum FileStatus {
        MISSING,
        NOT_EXECUTABLE,
        EXECUTABLE
    }

    /** A small text file's content, if it exists (for .env.example). */
    Optional<String> readFile(Path file);
}
