package io.github.louch.devdesk.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ShellCommandTest {

    @Test
    void singleWordIsKeptSoShellOperatorsWork() {
        assertThat(ShellCommand.join(List.of("npm run build && npm start"))).isEqualTo("npm run build && npm start");
    }

    @Test
    void severalWordsAreQuotedOnlyWhenNeeded() {
        assertThat(ShellCommand.join(List.of("echo", "a  b", "it's", "--port=3000")))
                .isEqualTo("echo 'a  b' 'it'\\''s' --port=3000");
    }

    @Test
    void nameIsTheProgramWithoutItsFolder() {
        assertThat(ShellCommand.nameOf("/usr/bin/npm run dev")).isEqualTo("npm");
    }
}
