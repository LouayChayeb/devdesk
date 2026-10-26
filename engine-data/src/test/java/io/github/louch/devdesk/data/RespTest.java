package io.github.louch.devdesk.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The Redis reply parser without a socket. The broken cases were found by RespFuzzTest. */
class RespTest {

    private static Resp.Reply read(String bytes) throws IOException {
        return Resp.readReply(new ByteArrayInputStream(bytes.getBytes(StandardCharsets.ISO_8859_1)));
    }

    @Test
    void everyReplyTypeIsRead() throws IOException {
        assertThat(read("+OK\r\n")).isEqualTo(new Resp.Reply.Simple("OK"));
        assertThat(read(":42\r\n")).isEqualTo(new Resp.Reply.Int(42));
        assertThat(read("$-1\r\n")).isEqualTo(new Resp.Reply.Bulk(null));
        assertThat(read("*2\r\n+a\r\n:1\r\n"))
                .isEqualTo(new Resp.Reply.Array(List.of(new Resp.Reply.Simple("a"), new Resp.Reply.Int(1))));
    }

    @Test
    void textWhereANumberShouldBeIsAnIoErrorNotACrash() {
        assertThatThrownBy(() -> read(":]?\r\n"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("is this really Redis?");
        assertThatThrownBy(() -> read("$99999999999\r\n")).isInstanceOf(IOException.class);
    }

    @Test
    void aValueCutShortIsAnEofNotAShortValue() {
        assertThatThrownBy(() -> read("$10\r\nabc")).isInstanceOf(EOFException.class);
    }

    @Test
    void aHugeArrayCountAllocatesNothingBeforeItsItemsArrive() {
        assertThatThrownBy(() -> read("*2000000000\r\n")).isInstanceOf(EOFException.class);
    }
}
