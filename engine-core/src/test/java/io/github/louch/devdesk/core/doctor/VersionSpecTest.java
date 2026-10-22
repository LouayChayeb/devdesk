package io.github.louch.devdesk.core.doctor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class VersionSpecTest {

    private static boolean ok(String spec, String installed) {
        return VersionSpec.satisfies(spec, VersionSpec.parse(installed).orElseThrow())
                .orElseThrow();
    }

    @Test
    void readsVersionsFromToolOutput() {
        assertThat(VersionSpec.parse("v22.3.0")).contains(List.of(22, 3, 0));
        assertThat(VersionSpec.parse("openjdk version \"25\" 2025-09-16")).contains(List.of(25));
        assertThat(VersionSpec.parse("Python 3.12.3")).contains(List.of(3, 12, 3));
        assertThat(VersionSpec.parse("nothing here")).isEmpty();
    }

    @Test
    void theWaysProjectsWriteVersions() {
        assertThat(ok("22", "v22.3.0")).isTrue();
        assertThat(ok("22", "v18.19.0")).isFalse();
        assertThat(ok("v22.3.0", "22.3.0")).isTrue();
        assertThat(ok("22.x", "22.9.1")).isTrue();
        assertThat(ok("^22.1", "22.0.5")).isFalse();
        assertThat(ok("^22.1", "22.4.0")).isTrue();
        assertThat(ok("~3.12", "3.13.0")).isFalse();
        assertThat(ok(">=20", "22.0.0")).isTrue();
        assertThat(ok(">=18 <21", "22.0.0")).isFalse();
        assertThat(ok("18 || 20", "20.1.0")).isTrue();
        assertThat(ok("3.12", "3.11.9")).isFalse(); // a minor version matters for Python
    }

    @Test
    void whatIsntUnderstoodIsNotGuessed() {
        assertThat(VersionSpec.satisfies("lts/*", List.of(22))).isEmpty();
        assertThat(VersionSpec.suggestion("^22.1")).isEqualTo("22.1");
        assertThat(VersionSpec.suggestion(">=20")).isEqualTo("20");
    }
}
