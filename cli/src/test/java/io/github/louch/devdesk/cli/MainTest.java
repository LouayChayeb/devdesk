package io.github.louch.devdesk.cli;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MainTest {

    @Test
    void greetingNamesTheProgram() {
        assertThat(Main.greeting()).startsWith("devdesk cli");
    }
}
