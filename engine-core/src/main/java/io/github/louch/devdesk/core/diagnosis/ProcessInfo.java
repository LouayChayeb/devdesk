package io.github.louch.devdesk.core.diagnosis;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

/**
 * Facts about one process on the machine, for explanations.
 *
 * @param workingDirectory its current folder; only visible for your own processes
 * @param command its command line, e.g. "node server.js"
 */
public record ProcessInfo(
        long pid, long parentPid, String command, Optional<Path> workingDirectory, Optional<Instant> startedAt) {}
