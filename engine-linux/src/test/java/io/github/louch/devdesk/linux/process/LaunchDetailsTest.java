package io.github.louch.devdesk.linux.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.louch.devdesk.core.process.OutputLine;
import io.github.louch.devdesk.core.process.ProcessLaunchSpec;
import io.github.louch.devdesk.core.process.RunningProcess;
import io.github.louch.devdesk.core.process.Signal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** What a started process gets (its environment, no stdin) and what the runtime refuses or cleans up. */
class LaunchDetailsTest {

    private static final boolean SYSTEMD =
            LinuxProcessRuntime.detect(Clock.systemUTC(), new Random()).usesSystemdScope();

    @TempDir
    Path dir;

    private final Queue<OutputLine> lines = new ConcurrentLinkedQueue<>();

    private LinuxProcessRuntime runtime(boolean scope) {
        assumeTrue(!scope || SYSTEMD, "no systemd user session on this machine");
        return new LinuxProcessRuntime(
                scope, Clock.systemUTC(), new Random(), Optional.of(new GroupLeftovers(dir.resolve("groups"))));
    }

    /** Runs {@code command} to its end and returns what it printed on stdout. */
    private List<String> run(LinuxProcessRuntime runtime, String owner, String command, Map<String, String> env)
            throws Exception {
        var p = runtime.start(new ProcessLaunchSpec(owner, "svc", command, dir, env), lines::add);
        runtime.onExit(p).get(10, TimeUnit.SECONDS);
        return lines.stream().map(OutputLine::text).toList();
    }

    private static List<String> names(List<String> envLines) {
        return envLines.stream().map(l -> l.substring(0, l.indexOf('='))).toList();
    }

    @Test
    void groupModeGivesExactlyTheSpecsEnvironmentPlusTheMarker() throws Exception {
        var out = run(runtime(false), "shop-1234", "env", Map.of("PATH", "/usr/bin:/bin", "APP_MODE", "dev"));

        // Nothing of DevDesk's own environment (HOME, USER, its secrets) leaks in; sh adds only PWD-like basics.
        assertThat(names(out))
                .contains("PATH", "APP_MODE", GroupLeftovers.MARKER)
                .doesNotContain("HOME", "USER", "XDG_RUNTIME_DIR", "DBUS_SESSION_BUS_ADDRESS");
        assertThat(out).contains("APP_MODE=dev");
        assertThat(out.stream().filter(l -> l.startsWith(GroupLeftovers.MARKER + "=")))
                .singleElement()
                .satisfies(l -> assertThat(l).matches(GroupLeftovers.MARKER + "=[0-9a-f]{24}"));
    }

    @Test
    void eachFamilyGetsItsOwnMarker() throws Exception {
        var runtime = runtime(false);
        String first = run(runtime, "shop-1234", "printf '%s\\n' \"$DEVDESK_GROUP\"", Map.of())
                .getLast();
        lines.clear();
        String second = run(runtime, "shop-1234", "printf '%s\\n' \"$DEVDESK_GROUP\"", Map.of())
                .getLast();

        assertThat(first).isNotEqualTo(second).hasSize(24);
    }

    @Test
    void withoutAProjectThereIsNoMarkerAndNoRecord() throws Exception {
        var out = run(runtime(false), "", "env", Map.of("PATH", "/usr/bin:/bin"));

        assertThat(names(out)).doesNotContain(GroupLeftovers.MARKER);
        assertThat(dir.resolve("groups")).doesNotExist();
    }

    @Test
    void scopeModeNeedsNoMarkerButPassesWhatSystemdRunNeeds() throws Exception {
        assumeTrue(System.getenv("XDG_RUNTIME_DIR") != null);
        var out = run(runtime(true), "shop-1234", "env", Map.of("PATH", "/usr/bin:/bin"));

        assertThat(names(out)).contains("XDG_RUNTIME_DIR").doesNotContain(GroupLeftovers.MARKER, "HOME");
        assertThat(out).contains("XDG_RUNTIME_DIR=" + System.getenv("XDG_RUNTIME_DIR"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void servicesGetNoStdinSoAReaderEndsInsteadOfWaiting(boolean scope) throws Exception {
        var runtime = runtime(scope);
        var p = runtime.start(
                new ProcessLaunchSpec("cat-test", "cat; echo eof", dir, Map.of("PATH", "/usr/bin:/bin")), lines::add);

        runtime.onExit(p).get(5, TimeUnit.SECONDS); // with stdin left open, cat would wait forever
        assertThat(lines).extracting(OutputLine::text).containsExactly("eof");
    }

    @Test
    void aFamilyThatEndedLeavesNoRecord() throws Exception {
        var runtime = runtime(false);
        var p = runtime.start(
                new ProcessLaunchSpec("shop-1234", "svc", "true", dir, Map.of("PATH", "/usr/bin:/bin")), lines::add);
        runtime.onExit(p).get(5, TimeUnit.SECONDS);

        Path records = dir.resolve("groups").resolve("shop-1234");
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            try (var files = Files.list(records)) {
                if (files.findAny().isEmpty()) {
                    break;
                }
            }
            Thread.sleep(20);
        }
        try (var files = Files.list(records)) {
            assertThat(files.toList()).isEmpty();
        }
        assertThat(runtime.members(p)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aProcessItDidntStartIsNeverTouched(boolean scope) {
        var runtime = runtime(scope);
        var stranger = new RunningProcess("init", 1, Instant.EPOCH);

        assertThatThrownBy(() -> runtime.signal(stranger, Signal.KILL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("'init' (PID 1) was not started by this runtime; refusing to touch it");
        assertThatThrownBy(() -> runtime.members(stranger)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unitNamesCarryTheProjectKeyAndOnlySafeCharacters() {
        assertThat(LinuxProcessRuntime.unitName("shop-70fadd7e", "web app", 0x1f))
                .isEqualTo("devdesk-shop-70fadd7e--web_app-0000001f");
        assertThat(LinuxProcessRuntime.unitName("", "api/v2", -1)).isEqualTo("devdesk-api_v2-ffffffff");
        assertThat(LinuxProcessRuntime.unitName("a b", "x", 0)).isEqualTo("devdesk-a_b--x-00000000");
    }
}
