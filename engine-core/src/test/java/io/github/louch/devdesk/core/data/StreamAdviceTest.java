package io.github.louch.devdesk.core.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class StreamAdviceTest {

    @Test
    void aQuietConsumerHoldingMessagesProbablyCrashed() {
        var info = new KeyValueBrowser.StreamInfo(
                50,
                "1-0",
                "50-0",
                List.of(new KeyValueBrowser.Group(
                        "mailer",
                        12,
                        "40-0",
                        10,
                        List.of(
                                new KeyValueBrowser.Consumer("worker-1", 12, Duration.ofMinutes(192)),
                                new KeyValueBrowser.Consumer("worker-2", 0, Duration.ofHours(5))))));

        assertThat(StreamAdvice.of("jobs", info))
                .singleElement()
                .asString()
                .contains("consumer worker-1 took 12 messages")
                .contains("quiet for 3 h 12 min")
                .contains("XAUTOCLAIM jobs mailer");
    }

    @Test
    void aGroupWithoutConsumersOrFarBehindIsNamed() {
        var info = new KeyValueBrowser.StreamInfo(
                5000,
                "1-0",
                "5000-0",
                List.of(
                        new KeyValueBrowser.Group("audit", 0, "0-0", 5000, List.of()),
                        new KeyValueBrowser.Group(
                                "mailer",
                                0,
                                "100-0",
                                4900,
                                List.of(new KeyValueBrowser.Consumer("w", 0, Duration.ZERO)))));

        assertThat(StreamAdvice.of("jobs", info))
                .containsExactly(
                        "Nobody reads group audit: it has no consumers, and 5000 new messages wait for it.",
                        "Group mailer is 4900 messages behind: its consumers don't keep up, or are stopped.");
    }

    @Test
    void keysSurviveTheFileRoundTrip() {
        var keys = List.of(new KeyValueBrowser.KeyDump("a", "string", -1, new byte[] {0, 1, (byte) 0xff}));

        var back = KeyDumpFile.read(KeyDumpFile.write(keys));

        assertThat(back).singleElement().satisfies(k -> {
            assertThat(k.key()).isEqualTo("a");
            assertThat(k.ttlMillis()).isEqualTo(-1);
            assertThat(k.payload()).containsExactly(0, 1, 0xff);
        });
    }
}
