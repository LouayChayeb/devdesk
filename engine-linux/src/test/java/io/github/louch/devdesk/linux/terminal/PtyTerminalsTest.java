package io.github.louch.devdesk.linux.terminal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.louch.devdesk.core.terminal.Terminals;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real shells on real pseudo-terminals. */
class PtyTerminalsTest {

    private static final Map<String, String> ENV = Map.of("PATH", System.getenv("PATH"), "GREETING", "hello");

    @TempDir
    Path folder;

    private final PtyTerminals terminals = new PtyTerminals();

    @Test
    void theProgramRunsOnARealTerminalInItsFolderWithItsEnvironment() throws IOException {
        try (var t = terminals.open(
                List.of("/bin/sh", "-c", "[ -t 0 ] && echo is-a-tty; pwd; echo $GREETING $TERM; stty size"),
                folder,
                ENV,
                100,
                30)) {
            String out = readAll(t);
            assertThat(out).contains("is-a-tty", folder.toString(), "hello xterm-256color", "30 100");
            assertThat(t.exitCode()).isEqualTo(OptionalInt.of(0));
        }
    }

    @Test
    void theTerminalIsTheProgramsControllingTerminal() throws IOException {
        // ps shows "?" for a process without a controlling terminal; "pts/N" means Ctrl+C will reach it.
        try (var t = terminals.open(List.of("/bin/sh", "-c", "ps -o tty= -p $$"), folder, ENV, 80, 24)) {
            assertThat(readAll(t)).contains("pts/");
        }
    }

    @Test
    void whatTheUserTypesReachesTheProgram() throws IOException {
        try (var t = terminals.open(List.of("/bin/sh", "-c", "read line; echo got:$line"), folder, ENV, 80, 24)) {
            t.write("abc\r".getBytes(StandardCharsets.UTF_8)); // Enter sends \r; the terminal turns it into \n
            assertThat(readAll(t)).contains("got:abc");
        }
    }

    @Test
    void resizingIsSeenByTheProgram() throws IOException {
        try (var t = terminals.open(List.of("/bin/sh", "-c", "read x; stty size"), folder, ENV, 80, 24)) {
            t.resize(120, 40);
            t.write("\r".getBytes(StandardCharsets.UTF_8));
            assertThat(readAll(t)).contains("40 120");
        }
    }

    @Test
    void closingEndsAProgramThatIsStillRunning() throws Exception {
        var t = terminals.open(List.of("/bin/sh", "-c", "sleep 600"), folder, ENV, 80, 24);
        long start = System.nanoTime();
        t.close();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
        assertThat(t.exitCode()).isPresent();
        assertThat(t.read(new byte[16])).isEqualTo(-1);
        t.close(); // twice is fine
    }

    private static String readAll(Terminals.Terminal t) throws IOException {
        var out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int n;
        while ((n = t.read(buffer)) != -1) {
            out.write(buffer, 0, n);
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
