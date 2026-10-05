package io.github.louch.devdesk.linux.process;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ClockTicksTest {

    @Test
    void kernelTicksAre100PerSecondAsTicksSinceBootAssumes() throws Exception {
        Process getconf = new ProcessBuilder("getconf", "CLK_TCK").start();
        String ticks = new String(getconf.getInputStream().readAllBytes()).trim();

        assertThat(ticks).isEqualTo("100");
    }

    @Test
    void ourOwnStartTimeIsBeforeNow() {
        long me = ProcStat.read(ProcessHandle.current().pid()).orElseThrow().startTime();

        assertThat(me).isLessThanOrEqualTo(LinuxProcessRuntime.ticksSinceBoot() + 1);
    }
}
