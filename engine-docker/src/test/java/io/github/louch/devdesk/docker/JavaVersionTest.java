package io.github.louch.devdesk.docker;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JavaVersionTest {

    @Test
    void testsRunOnJava25() {
        // Fails if the build ever picks a different JDK than the toolchain asks for.
        assertThat(Runtime.version().feature()).isEqualTo(25);
    }
}
