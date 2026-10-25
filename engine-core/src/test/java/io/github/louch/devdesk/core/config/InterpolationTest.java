package io.github.louch.devdesk.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class InterpolationTest {

    private static Optional<String> resolve(String text, Map<String, String> env, StringBuilder missing) {
        return Interpolation.resolve(text, env, missing);
    }

    @Test
    void variablesDefaultsAndEscapedDollars() {
        var missing = new StringBuilder();

        assertThat(resolve("${A}-${B:-two}-$${C}-$$-$5", Map.of("A", "one"), missing))
                .contains("one-two-${C}-$-$5");
        assertThat(missing).isEmpty();
    }

    @Test
    void aSetVariableWinsOverItsDefault() {
        assertThat(resolve("${B:-two}", Map.of("B", "set"), new StringBuilder()))
                .contains("set");
        assertThat(resolve("${B:-}", Map.of(), new StringBuilder())).contains("");
    }

    @Test
    void anUnknownVariableWithoutDefaultIsNamed() {
        var missing = new StringBuilder();

        assertThat(resolve("x ${NOPE} y", Map.of(), missing)).isEmpty();
        assertThat(missing).hasToString("NOPE");
    }

    @Test
    void replacementTextIsTakenLiterally() {
        // A value holding $ or \ must not be read as a regex replacement group.
        assertThat(resolve("${P}", Map.of("P", "a$1\\b"), new StringBuilder())).contains("a$1\\b");
    }
}
