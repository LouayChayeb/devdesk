package io.github.louch.devdesk.docker;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.louch.devdesk.core.process.OutputLine;
import io.github.louch.devdesk.core.process.StreamKind;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class FrameLinesTest {

    private final List<OutputLine> lines = new ArrayList<>();
    private final FrameLines frames = new FrameLines(lines::add);

    @Test
    void linesSplitAcrossFramesAndStreamsAreRebuilt() {
        frames.accept(StreamKind.STDOUT, "hel".getBytes(StandardCharsets.UTF_8));
        frames.accept(StreamKind.STDERR, "warn\n".getBytes(StandardCharsets.UTF_8));
        frames.accept(StreamKind.STDOUT, "lo\r\nlast".getBytes(StandardCharsets.UTF_8));
        frames.flush();

        assertThat(lines)
                .containsExactly(
                        new OutputLine(StreamKind.STDERR, "warn"),
                        new OutputLine(StreamKind.STDOUT, "hello"),
                        new OutputLine(StreamKind.STDOUT, "last"));
    }

    @Test
    void characterCutInHalfBetweenFramesSurvives() {
        byte[] e = "é\n".getBytes(StandardCharsets.UTF_8); // é is two bytes: C3 A9
        frames.accept(StreamKind.STDOUT, Arrays.copyOfRange(e, 0, 1));
        frames.accept(StreamKind.STDOUT, Arrays.copyOfRange(e, 1, e.length));

        assertThat(lines).containsExactly(new OutputLine(StreamKind.STDOUT, "é"));
    }
}
