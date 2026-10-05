package io.github.louch.devdesk.linux.process;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Captures the environment the user's login shell builds (nvm, sdkman, pyenv, ~/.local/bin ...).
 *
 * <p>A program started from a desktop menu doesn't run ~/.profile or ~/.bashrc, so {@code npm} can be "not found"
 * even though it works in a terminal. Running {@code $SHELL -l -i -c 'env -0'} once rebuilds that environment.
 */
public final class LoginShellEnvironment {

    private static final String START = "__DEVDESK_ENV_START__";
    private static final String END = "__DEVDESK_ENV_END__";
    // Shell bookkeeping, not settings: DevDesk sets the working directory itself.
    private static final Set<String> SHELL_INTERNAL = Set.of("_", "SHLVL", "PWD", "OLDPWD");

    /** The outcome: the variables, or a reason why they couldn't be read (never a silent empty map). */
    public sealed interface Result {
        /** The login shell ran and printed its environment. */
        record Captured(Map<String, String> variables) implements Result {}

        /** The shell failed or hung. {@code reason} says what to check. */
        record Failed(String reason) implements Result {}
    }

    private LoginShellEnvironment() {}

    /** Runs {@code shell} as an interactive login shell with the given HOME, for at most {@code timeout}. */
    public static Result capture(Path shell, Path home, Duration timeout) {
        // -l = login (reads ~/.profile, ~/.bash_profile), -i = interactive (reads ~/.bashrc, where nvm usually lives).
        // Profiles may print anything (motd, greetings), so the real output is wrapped in markers.
        // The leading \0 separates the marker from any noise printed just before it without a newline.
        String script = "printf '\\0%s\\0' " + START + "; env -0; printf '%s\\0' " + END;
        ProcessBuilder builder = new ProcessBuilder(shell.toString(), "-l", "-i", "-c", script)
                .redirectInput(ProcessBuilder.Redirect.from(Path.of("/dev/null").toFile()))
                // Without a terminal, -i always complains "no job control" on stderr: expected noise.
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().put("HOME", home.toString());

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            return new Result.Failed("Could not run your shell " + shell + ": " + e.getMessage());
        }
        CompletableFuture<byte[]> output = CompletableFuture.supplyAsync(() -> readAll(process));
        try {
            return parse(output.get(timeout.toMillis(), TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            // We started this shell, so killing it and whatever it started is allowed.
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            return new Result.Failed("Your login shell (" + shell + ") did not finish within " + timeout.toSeconds()
                    + "s. Something in ~/.profile or ~/.bashrc is slow or waits for input");
        } catch (ExecutionException e) {
            return new Result.Failed(
                    "Reading your shell's output failed: " + e.getCause().getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result.Failed("Interrupted while reading your login shell environment");
        }
    }

    /** Extracts the NUL-separated KEY=VALUE entries between the two markers. */
    static Result parse(byte[] output) {
        // NUL separates entries because values may contain newlines; NUL is the one byte that can't appear in them.
        List<String> parts = Arrays.asList(new String(output, StandardCharsets.UTF_8).split("\0", -1));
        int start = parts.indexOf(START);
        int end = parts.indexOf(END);
        if (start < 0 || end < start) {
            return new Result.Failed("Your shell exited before printing its environment (does ~/.bashrc call exit?)");
        }
        Map<String, String> variables = new HashMap<>();
        for (String entry : parts.subList(start + 1, end)) {
            int eq = entry.indexOf('=');
            if (eq > 0 && !SHELL_INTERNAL.contains(entry.substring(0, eq))) {
                variables.put(entry.substring(0, eq), entry.substring(eq + 1)); // values may contain '='
            }
        }
        return new Result.Captured(Map.copyOf(variables));
    }

    private static byte[] readAll(Process process) {
        try {
            return process.getInputStream().readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
