package io.github.louch.devdesk.linux.process;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProcStatTest {

    @Test
    void programNameWithSpacesAndParenthesesDoesNotShiftFields() {
        // Real layout: pid (comm) state ppid pgrp session tty tpgid flags ... field 22 = starttime
        String line = "4121 (my app) x)) S 1 4100 4100 0 -1 4194560 100 0 0 0 1 2 0 0 20 0 1 0 987654 1000 10";

        assertThat(ProcStat.parse(4121, line)).isEqualTo(new ProcStat(4121, 'S', 1, 4100, 987654));
    }

    @Test
    void ownProcessCanBeRead() {
        long me = ProcessHandle.current().pid();

        assertThat(ProcStat.read(me))
                .hasValueSatisfying(s -> assertThat(s.startTime()).isPositive());
    }
}
