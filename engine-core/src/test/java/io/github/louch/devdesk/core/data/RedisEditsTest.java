package io.github.louch.devdesk.core.data;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.louch.devdesk.core.data.KeyValueBrowser.Edit;
import java.util.List;
import org.junit.jupiter.api.Test;

class RedisEditsTest {

    @Test
    void eachEditIsThePlainRedisCommand() {
        assertThat(RedisEdits.commands("k", new Edit.SetText("hi"), "m"))
                .containsExactly(List.of("SET", "k", "hi", "KEEPTTL"));
        assertThat(RedisEdits.commands("h", new Edit.HashSet("name", "Ada"), "m"))
                .containsExactly(List.of("HSET", "h", "name", "Ada"));
        assertThat(RedisEdits.commands("l", new Edit.ListPush("x", true), "m"))
                .containsExactly(List.of("LPUSH", "l", "x"));
        assertThat(RedisEdits.commands("z", new Edit.ScoreSet("ada", 2.0), "m"))
                .containsExactly(List.of("ZADD", "z", "2", "ada"));
        assertThat(RedisEdits.commands("z", new Edit.ScoreSet("ada", 1.5), "m"))
                .containsExactly(List.of("ZADD", "z", "1.5", "ada"));
    }

    @Test
    void aListElementIsRemovedByPositionInsideATransaction() {
        assertThat(RedisEdits.commands("l", new Edit.ListRemove(3), "__mark__"))
                .containsExactly(
                        List.of("MULTI"),
                        List.of("LSET", "l", "3", "__mark__"),
                        List.of("LREM", "l", "1", "__mark__"),
                        List.of("EXEC"));
    }
}
