package io.github.louch.devdesk.core.process;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * The "port" core uses to run OS processes. Linux implements it in engine-linux; tests use a fake.
 *
 * <p>Every method acts on the <em>whole</em> family the process started (children, grandchildren), never on one PID only.
 */
public interface ProcessRuntime {

    /** Starts the command. Every output line is passed to {@code output}, from a background thread. */
    RunningProcess start(ProcessLaunchSpec spec, Consumer<OutputLine> output) throws LaunchException;

    /** Sends {@code signal} to every process the given one owns. Does nothing if they are all gone already. */
    void signal(RunningProcess process, Signal signal);

    /** Completes when the top process has exited. */
    CompletableFuture<ExitStatus> onExit(RunningProcess process);

    /** PIDs of every process still alive in the family. Empty means nothing is left behind. */
    Set<Long> members(RunningProcess process);
}
