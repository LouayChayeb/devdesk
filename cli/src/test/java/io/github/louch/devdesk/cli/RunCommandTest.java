package io.github.louch.devdesk.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.louch.devdesk.linux.process.LinuxProcessRuntime;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/** Runs the real command against the real kernel, capturing what the user would see. */
class RunCommandTest {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(String... args) {
        var runtime = LinuxProcessRuntime.detect(Clock.systemUTC(), new SecureRandom());
        var command = new RunCommand(
                runtime,
                Path.of("/tmp"),
                System.getenv(),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new CommandLine(command).execute(args);
    }

    @Test
    void passesOutputThroughAndReturnsTheExitCode() {
        int code = run("--", "sh", "-c", "echo hello; echo oops >&2; exit 3");

        assertThat(code).isEqualTo(3);
        assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo("hello\n");
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("oops").contains("exited with error code 3");
    }

    @Test
    void missingCommandIsExplained() {
        int code = run("--", "no-such-command-xyz");

        assertThat(code).isEqualTo(127);
        assertThat(err.toString(StandardCharsets.UTF_8))
                .contains("command not found")
                .contains("PATH");
    }

    @Test
    void childrenLeftBehindAfterExitAreStopped() {
        int code = run("--grace", "1", "--", "sleep 1000 & echo started; exit 0");

        assertThat(code).isZero();
        assertThat(err.toString(StandardCharsets.UTF_8))
                .contains("left 1 process(es) running")
                .contains("stopped cleanly, nothing left running");
    }
}
