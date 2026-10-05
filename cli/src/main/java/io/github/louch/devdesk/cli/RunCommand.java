package io.github.louch.devdesk.cli;

import io.github.louch.devdesk.core.process.ExitStatus;
import io.github.louch.devdesk.core.process.GracefulStop;
import io.github.louch.devdesk.core.process.LaunchException;
import io.github.louch.devdesk.core.process.OutputLine;
import io.github.louch.devdesk.core.process.ProcessLaunchSpec;
import io.github.louch.devdesk.core.process.ProcessRuntime;
import io.github.louch.devdesk.core.process.RunningProcess;
import io.github.louch.devdesk.core.process.StopResult;
import io.github.louch.devdesk.core.process.StreamKind;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** {@code devdesk run -- <command>}: runs one command in the foreground and never leaves a process behind. */
@Command(
        name = "run",
        description = "Run one command, show its output, and stop everything it started when it ends or on Ctrl+C.")
final class RunCommand implements Callable<Integer> {

    @Option(
            names = "--grace",
            defaultValue = "10",
            paramLabel = "SECONDS",
            description = "How long to wait after SIGTERM before SIGKILL (default: ${DEFAULT-VALUE}).")
    long graceSeconds;

    @Parameters(arity = "1..*", paramLabel = "COMMAND", description = "The command, after --.")
    List<String> words;

    private final ProcessRuntime runtime;
    private final Path workingDirectory;
    private final Map<String, String> environment;
    private final PrintStream out;
    private final PrintStream err;
    private final AtomicBoolean stopping = new AtomicBoolean();

    RunCommand(
            ProcessRuntime runtime,
            Path workingDirectory,
            Map<String, String> environment,
            PrintStream out,
            PrintStream err) {
        this.runtime = runtime;
        this.workingDirectory = workingDirectory;
        this.environment = environment;
        this.out = out;
        this.err = err;
    }

    @Override
    public Integer call() throws InterruptedException {
        String commandLine = ShellCommand.join(words);
        String name = ShellCommand.nameOf(commandLine);
        // Printed before starting: once started, the command's own output may come first.
        err.println("devdesk: starting " + name + ". Ctrl+C stops it and everything it started.");
        RunningProcess process;
        try {
            process =
                    runtime.start(new ProcessLaunchSpec(name, commandLine, workingDirectory, environment), this::print);
        } catch (LaunchException e) {
            err.println("devdesk: " + e.getMessage());
            return 1;
        }

        // Ctrl+C: the terminal sends SIGINT to DevDesk only (the command runs in its own session), and the JVM then
        // runs shutdown hooks. Ours performs the controlled stop before the JVM exits with code 130.
        Thread onCtrlC = new Thread(() -> stop(process, "Ctrl+C"));
        Runtime.getRuntime().addShutdownHook(onCtrlC);

        ExitStatus exit = runtime.onExit(process).join();
        if (stopping.get()) {
            return exit.code(); // the shutdown hook owns the rest; the JVM is exiting
        }
        err.println("devdesk: " + name + ": " + exit.explanation());
        Set<Long> leftovers = runtime.members(process);
        if (!leftovers.isEmpty()) {
            err.println("devdesk: it left " + leftovers.size() + " process(es) running " + leftovers
                    + "; stopping them so nothing is orphaned");
            stop(process, "leftovers after exit");
        }
        Runtime.getRuntime().removeShutdownHook(onCtrlC);
        return exit.code();
    }

    private void stop(RunningProcess process, String reason) {
        if (!stopping.compareAndSet(false, true)) {
            return; // only one stop sequence, even if Ctrl+C arrives while we stop leftovers
        }
        Duration grace = Duration.ofSeconds(graceSeconds);
        err.println("devdesk: stopping " + process.name() + " (PID " + process.pid() + ", " + reason
                + "): SIGTERM, SIGKILL after "
                + grace.toSeconds() + "s");
        try {
            report(new GracefulStop(runtime).stop(process, grace), grace);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void report(StopResult result, Duration grace) {
        switch (result) {
            case StopResult.Stopped(var _, boolean forced)
            when forced ->
                err.println("devdesk: force-killed with SIGKILL: it ignored SIGTERM for " + grace.toSeconds() + "s");
            case StopResult.Stopped _ -> err.println("devdesk: stopped cleanly, nothing left running");
            case StopResult.LeftBehind(var _, Set<Long> pids) ->
                err.println("devdesk: WARNING: still running even after SIGKILL: " + pids
                        + ". They escaped DevDesk. Inspect them with: ps -o pid,ppid,cmd -p "
                        + String.join(",", pids.stream().map(String::valueOf).toList()));
        }
    }

    private void print(OutputLine line) {
        // Keep the command's stdout on our stdout, so `devdesk run -- ... | grep x` works as expected.
        (line.stream() == StreamKind.STDOUT ? out : err).println(line.text());
    }
}
