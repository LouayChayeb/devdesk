package io.github.louch.devdesk.linux;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LinuxHostTest {

    @Test
    void runsOnLinux() {
        assertThat(System.getProperty("os.name")).isEqualTo("Linux");
    }

    @Test
    void procFilesystemIsAvailable() {
        // /proc is a fake folder the Linux kernel fills with live info about every process.
        // The engine will read it later (M1, M5), so check it's there from day one.
        assertThat(Files.isDirectory(Path.of("/proc/self"))).isTrue();
    }
}
