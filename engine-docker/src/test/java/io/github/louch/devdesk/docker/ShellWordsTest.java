package io.github.louch.devdesk.docker;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ShellWordsTest {

    @Test
    void quotesKeepWordsTogether() {
        assertThat(ShellWords.split("sh -c 'echo a; exit 3'")).contains(List.of("sh", "-c", "echo a; exit 3"));
        assertThat(ShellWords.split("postgres -c \"log_statement=all\"  -c x\\ y"))
                .contains(List.of("postgres", "-c", "log_statement=all", "-c", "x y"));
        assertThat(ShellWords.split("echo '' \"a\\\"b\"")).contains(List.of("echo", "", "a\"b"));
    }

    @Test
    void unclosedQuoteIsReportedNotGuessed() {
        assertThat(ShellWords.split("sh -c 'oops")).isEmpty();
    }
}
