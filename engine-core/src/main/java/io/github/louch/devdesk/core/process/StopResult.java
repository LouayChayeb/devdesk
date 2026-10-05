package io.github.louch.devdesk.core.process;

import java.util.Optional;
import java.util.Set;

/** What happened when DevDesk stopped a process family. */
public sealed interface StopResult {

    /** Every process is gone. {@code forced} is true if SIGKILL was needed after the grace period. */
    record Stopped(ExitStatus exit, boolean forced) implements StopResult {}

    /**
     * Some processes are still alive even after SIGKILL: a bug or an escaped process that must be reported, never
     * ignored. {@code exit} is empty if even the top process didn't exit.
     */
    record LeftBehind(Optional<ExitStatus> exit, Set<Long> pids) implements StopResult {}
}
