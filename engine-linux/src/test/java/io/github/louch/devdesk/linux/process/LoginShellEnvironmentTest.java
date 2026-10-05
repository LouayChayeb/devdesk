package io.github.louch.devdesk.linux.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.louch.devdesk.linux.process.LoginShellEnvironment.Result;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LoginShellEnvironmentTest {

    @TempDir
    Path home;

    @Test
    void readsWhatTheProfileExportsAndIgnoresItsNoise() throws Exception {
        Files.writeString(home.resolve(".bash_profile"), """
                echo "Welcome! This greeting must not end up in the environment"
                export DEVDESK_PROBE="line one
                line two = still the value"
                export PATH="$HOME/fake-nvm/bin:$PATH"
                """);

        Result result = LoginShellEnvironment.capture(Path.of("/bin/bash"), home, Duration.ofSeconds(10));

        assertThat(result).isInstanceOfSatisfying(Result.Captured.class, captured -> {
            assertThat(captured.variables())
                    .containsEntry("DEVDESK_PROBE", "line one\nline two = still the value")
                    .containsEntry("HOME", home.toString())
                    .doesNotContainKeys("SHLVL", "_", "PWD");
            assertThat(captured.variables().get("PATH")).startsWith(home + "/fake-nvm/bin:");
        });
    }

    @Test
    void hangingProfileIsCutOffWithAHelpfulReason() throws Exception {
        Files.writeString(home.resolve(".bash_profile"), "sleep 30\n");

        Result result = LoginShellEnvironment.capture(Path.of("/bin/bash"), home, Duration.ofSeconds(1));

        assertThat(result)
                .isInstanceOfSatisfying(
                        Result.Failed.class,
                        failed -> assertThat(failed.reason())
                                .contains("did not finish within 1s")
                                .contains("~/.profile"));
    }

    @Test
    void profileThatExitsEarlyIsReportedNotSilentlyEmpty() throws Exception {
        Files.writeString(home.resolve(".bash_profile"), "exit 0\n");

        Result result = LoginShellEnvironment.capture(Path.of("/bin/bash"), home, Duration.ofSeconds(10));

        assertThat(result).isInstanceOf(Result.Failed.class);
    }

    @Test
    void zshWorksToo() throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/usr/bin/zsh")), "zsh is not installed on this machine");
        Files.writeString(home.resolve(".zprofile"), "export DEVDESK_PROBE=zsh\n", StandardCharsets.UTF_8);

        Result result = LoginShellEnvironment.capture(Path.of("/usr/bin/zsh"), home, Duration.ofSeconds(10));

        assertThat(result)
                .isInstanceOfSatisfying(
                        Result.Captured.class, c -> assertThat(c.variables()).containsEntry("DEVDESK_PROBE", "zsh"));
    }
}
