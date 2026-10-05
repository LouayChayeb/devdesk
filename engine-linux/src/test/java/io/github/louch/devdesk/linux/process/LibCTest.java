package io.github.louch.devdesk.linux.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import org.junit.jupiter.api.Test;

class LibCTest {

    // Signal 0 sends nothing: the kernel only checks "does it exist, and may I signal it?". Safe to aim anywhere.
    private static final int EXISTENCE_CHECK = 0;

    @Test
    void ownProcessCanBeSignalled() {
        assertThat(LibC.kill(ProcessHandle.current().pid(), EXISTENCE_CHECK)).isZero();
    }

    @Test
    void missingProcessReportsEsrch() {
        // Above the kernel's maximum PID (/proc/sys/kernel/pid_max is at most 4194304), so it can't exist.
        assertThat(LibC.kill(9_999_999, EXISTENCE_CHECK)).isEqualTo(LibC.ESRCH);
    }

    @Test
    void someoneElsesProcessReportsEperm() {
        assumeFalse("root".equals(System.getProperty("user.name")), "root may signal anything");
        // PID 1 (init) belongs to root.
        assertThat(LibC.kill(1, EXISTENCE_CHECK)).isEqualTo(LibC.EPERM);
    }
}
