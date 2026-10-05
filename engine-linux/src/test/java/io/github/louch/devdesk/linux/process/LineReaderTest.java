package io.github.louch.devdesk.linux.process;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.louch.devdesk.core.process.OutputLine;
import io.github.louch.devdesk.core.process.StreamKind;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LineReaderTest {

    private final List<String> lines = new ArrayList<>();
    private final LineReader reader = new LineReader(
            new ByteArrayInputStream(new byte[0]), StreamKind.STDOUT, (OutputLine l) -> lines.add(l.text()));

    @Test
    void splitsOnNewlineAndDropsWindowsCarriageReturn() throws Exception {
        reader.split(new StringReader("one\ntwo\r\nlast-without-newline"));

        assertThat(lines).containsExactly("one", "two", "last-without-newline");
    }

    @Test
    void runawayLineIsCutIntoPieces() throws Exception {
        reader.split(new StringReader("a".repeat(40_000) + "\n"));

        assertThat(lines).extracting(String::length).containsExactly(16_384, 16_384, 7_232);
    }

    @Test
    void invalidUtf8BecomesReplacementCharInsteadOfCrashing() {
        byte[] bytes = {'o', 'k', (byte) 0xFF, '\n'};
        new LineReader(new ByteArrayInputStream(bytes), StreamKind.STDERR, l -> lines.add(l.text())).run();

        assertThat(lines).containsExactly("ok�");
        assertThat("ok�".getBytes(StandardCharsets.UTF_8)).hasSize(5);
    }
}
