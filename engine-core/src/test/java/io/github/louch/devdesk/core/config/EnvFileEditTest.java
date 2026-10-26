package io.github.louch.devdesk.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class EnvFileEditTest {

    private static final String FILE = "# database\nDB_PASSWORD=old\n\nexport LOG_LEVEL=info\n";

    @Test
    void changingAValueKeepsCommentsOrderAndExport() {
        assertThat(EnvFileEdit.set(FILE, "DB_PASSWORD", "new one"))
                .isEqualTo("# database\nDB_PASSWORD=\"new one\"\n\nexport LOG_LEVEL=info\n");
        assertThat(EnvFileEdit.set(FILE, "LOG_LEVEL", "debug")).contains("export LOG_LEVEL=debug\n");
    }

    @Test
    void aNewKeyGoesAtTheEndAndRemovingKeepsTheRest() {
        assertThat(EnvFileEdit.set(FILE, "PORT", "3000")).endsWith("export LOG_LEVEL=info\nPORT=3000\n");
        assertThat(EnvFileEdit.remove(FILE, "DB_PASSWORD")).isEqualTo("# database\n\nexport LOG_LEVEL=info\n");
        assertThat(EnvFileEdit.set("", "A", "1")).isEqualTo("A=1\n");
    }

    @Test
    void namesAndLineBreaksThatWouldBreakTheFileAreRefused() {
        assertThatThrownBy(() -> EnvFileEdit.set(FILE, "BAD KEY", "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EnvFileEdit.set(FILE, "A", "two\nlines")).isInstanceOf(IllegalArgumentException.class);
    }

    // The next three were found by the fuzzer (ConfigFuzzTest.envEditorRoundTrip): what the editor writes must be
    // what the reader reads.

    @Test
    void quotesAndBackslashesReadBackAsWritten() {
        String written = EnvFileEdit.set("", "A", "say \"hi\" C:\\dir");
        assertThat(written).isEqualTo("A=\"say \\\"hi\\\" C:\\\\dir\"\n");
        assertThat(EnvFile.parse(written)).containsEntry("A", "say \"hi\" C:\\dir");
    }

    @Test
    void aKeySetTwiceIsChangedWhereTheReaderLooks() {
        // Readers use the last line; changing the first would change nothing.
        String written = EnvFileEdit.set("A=1\nA=2\n", "A", "3");
        assertThat(written).isEqualTo("A=1\nA=3\n");
        assertThat(EnvFile.parse(written)).containsEntry("A", "3");
    }

    @Test
    void aLoneCarriageReturnSplitsLinesForBothReaderAndEditor() {
        String written = EnvFileEdit.set("A=1\rB=2", "B", "x");
        assertThat(EnvFile.parse(written)).containsEntry("A", "1").containsEntry("B", "x");
        assertThatThrownBy(() -> EnvFileEdit.set("", "A", "a\rb")).isInstanceOf(IllegalArgumentException.class);
    }
}
