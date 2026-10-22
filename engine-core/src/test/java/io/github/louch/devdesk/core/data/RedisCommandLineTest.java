package io.github.louch.devdesk.core.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class RedisCommandLineTest {

    @Test
    void wordsAreSplitLikeRedisCli() {
        assertThat(RedisCommandLine.split("  set greeting \"hello world\"  "))
                .containsExactly("set", "greeting", "hello world");
        assertThat(RedisCommandLine.split("set k 'it''s raw \\n'")).containsExactly("set", "k", "its raw \\n");
        assertThat(RedisCommandLine.split("set k \"a\\nb\\x41\\\"\"")).containsExactly("set", "k", "a\nbA\"");
        assertThat(RedisCommandLine.split("hset user:1 name \"\"")).containsExactly("hset", "user:1", "name", "");
        assertThat(RedisCommandLine.split("")).isEmpty();
    }

    @Test
    void anUnclosedQuoteIsAnError() {
        assertThatThrownBy(() -> RedisCommandLine.split("set k \"oops")).hasMessageContaining("unclosed \" quote");
    }

    @Test
    void serverWideCommandsAreConfirmedFirst() {
        assertThat(RedisCommandLine.risk(List.of("flushall"))).isPresent();
        assertThat(RedisCommandLine.risk(List.of("keys", "*"))).get().asString().contains("SCAN");
        assertThat(RedisCommandLine.risk(List.of("config", "set", "maxmemory", "1")))
                .isPresent();
        assertThat(RedisCommandLine.risk(List.of("config", "get", "maxmemory"))).isEmpty();
        assertThat(RedisCommandLine.risk(List.of("del", "user:1"))).isEmpty();
    }

    @Test
    void endlessCommandsAreRefusedWithAnAlternative() {
        assertThat(RedisCommandLine.refused(List.of("MONITOR")))
                .get()
                .asString()
                .contains("redis-cli");
        assertThat(RedisCommandLine.refused(List.of("select", "2"))).isPresent();
        assertThat(RedisCommandLine.refused(List.of("get", "k"))).isEmpty();
    }
}
