package io.github.louch.devdesk.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class TerminalWriterTest {

    /** A terminal nobody reads: every write blocks until the test opens the gate. */
    private static final class FrozenTerminal extends OutputStream {
        final CountDownLatch gate = new CountDownLatch(1);
        final ByteArrayOutputStream seen = new ByteArrayOutputStream();

        @Override
        public synchronized void write(int b) {
            try {
                gate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            seen.write(b);
        }

        synchronized String text() {
            return seen.toString(StandardCharsets.UTF_8);
        }
    }

    @Test
    void aFrozenTerminalNeverBlocksTheWriterAndSkippedLinesAreCounted() throws Exception {
        var frozen = new FrozenTerminal();
        var writer = new TerminalWriter(new PrintStream(frozen, true, StandardCharsets.UTF_8));

        long started = System.nanoTime();
        for (int i = 0; i < TerminalWriter.CAPACITY + 500; i++) {
            writer.stream().println("api | line " + i);
        }
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2)); // never waited

        frozen.gate.countDown(); // the terminal reads again
        writer.flush(Duration.ofSeconds(10));
        writer.line("■ api stopped");
        writer.flush(Duration.ofSeconds(10));

        // Lines arrive in order, then one notice where the skipped ones were, then the rest. Shown + skipped = all.
        String text = frozen.text();
        var notice = Pattern.compile("\n… (\\d+) lines not shown: the terminal wasn't reading")
                .matcher(text);
        assertThat(notice.find()).isTrue();
        long shown = text.lines().filter(l -> l.startsWith("api | line ")).count();
        assertThat(shown + Long.parseLong(notice.group(1))).isEqualTo(TerminalWriter.CAPACITY + 500);
        assertThat(text).startsWith("api | line 0\napi | line 1\n").endsWith("■ api stopped\n");
    }

    @Test
    void linesKeepTheirOrder() {
        var out = new ByteArrayOutputStream();
        var writer = new TerminalWriter(new PrintStream(out, true, StandardCharsets.UTF_8));

        writer.stream().println("one");
        writer.stream().println("two");
        writer.stream().print("thr");
        writer.stream().println("ee");
        writer.flush(Duration.ofSeconds(5));

        assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo("one\ntwo\nthree\n");
    }
}
