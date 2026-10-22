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
}
