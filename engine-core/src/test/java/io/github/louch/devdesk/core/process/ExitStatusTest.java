package io.github.louch.devdesk.core.process;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ExitStatusTest {

    @Test
    void signalDeathsAreDecodedFrom128PlusSignalNumber() {
        assertThat(ExitStatus.of(143)).isEqualTo(new ExitStatus.KilledBySignal(143, Signal.TERM));
        assertThat(ExitStatus.of(137)).isEqualTo(new ExitStatus.KilledBySignal(137, Signal.KILL));
        assertThat(ExitStatus.of(130)).isEqualTo(new ExitStatus.KilledBySignal(130, Signal.INT));
    }

    @Test
    void commandNotFoundPointsAtPath() {
        assertThat(ExitStatus.of(127).explanation())
                .contains("command not found")
                .contains("PATH");
    }

    @Test
    void ordinaryCodesStayExits() {
        assertThat(ExitStatus.of(0)).isEqualTo(new ExitStatus.Exited(0));
        assertThat(ExitStatus.of(1).explanation()).isEqualTo("exited with error code 1");
    }
}
