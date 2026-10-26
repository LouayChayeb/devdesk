package io.github.louch.devdesk.linux.process;

import static org.assertj.core.api.Assertions.assertThat;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;

/**
 * Fuzz test (Jazzer) for /proc/PID/stat. The kernel writes the numbers, but field 2 is the program's name, which any
 * program can set to anything (up to 15 bytes, spaces and ')' included). Whatever the name, the numbers DevDesk uses
 * to recognise its processes (parent, group, start time) must come out right.
 */
class ProcStatFuzzTest {

    @FuzzTest(maxDuration = "10s")
    void anyProgramName(FuzzedDataProvider data) {
        String name = data.consumeString(15).replace("\0", "");
        char state = data.pickValue(new Character[] {'R', 'S', 'D', 'Z', 'T', 'I'});
        long parent = data.consumeLong(0, 4_194_304);
        long group = data.consumeLong(0, 4_194_304);
        long start = data.consumeLong(0, Long.MAX_VALUE);
        StringBuilder line = new StringBuilder("4121 (" + name + ") " + state + " " + parent + " " + group);
        for (int field = 6; field <= 21; field++) {
            line.append(' ').append(data.consumeInt(0, 1_000_000));
        }
        line.append(' ').append(start).append(" 0 0 0\n");

        assertThat(ProcStat.parse(4121, line.toString())).isEqualTo(new ProcStat(4121, state, parent, group, start));
    }
}
