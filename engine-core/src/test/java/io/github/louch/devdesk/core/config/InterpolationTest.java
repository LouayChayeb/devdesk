package io.github.louch.devdesk.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.louch.devdesk.core.config.Interpolation.Result;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InterpolationTest {

    private static Result resolve(String text, Map<String, String> env) {
        return Interpolation.resolve(text, env);
    }

    @Test
    void variablesDefaultsAndEscapedDollars() {
        assertThat(resolve("${A}-${B:-two}-$${C}-$$-$5", Map.of("A", "one")))
                .isEqualTo(new Result.Resolved("one-two-${C}-$-$5"));
    }

    @Test
    void aSetVariableWinsOverItsDefault() {
        assertThat(resolve("${B:-two}", Map.of("B", "set"))).isEqualTo(new Result.Resolved("set"));
        assertThat(resolve("${B:-}", Map.of())).isEqualTo(new Result.Resolved(""));
    }

    @Test
    void withTheColonAnEmptyValueCountsAsUnset() {
        // Compose: ${VAR:-x} falls back when VAR is unset or empty, ${VAR-x} only when it's unset.
        assertThat(resolve("${E:-x}", Map.of("E", ""))).isEqualTo(new Result.Resolved("x"));
        assertThat(resolve("${E-x}", Map.of("E", ""))).isEqualTo(new Result.Resolved(""));
        assertThat(resolve("${E-x}", Map.of())).isEqualTo(new Result.Resolved("x"));
    }

    @Test
    void aRequiredVariableIsNamedWithTheProjectsMessage() {
        // The form full-stack-fastapi-template uses: it reached the app as the literal text before.
        assertThat(resolve("${SECRET_KEY:?Variable not set}", Map.of("SECRET_KEY", "s3")))
                .isEqualTo(new Result.Resolved("s3"));
        assertThat(resolve("postgresql://u:${PW:?Variable not set}@db/app", Map.of()))
                .isEqualTo(new Result.Unset("PW", "Variable not set"));
        assertThat(resolve("${PW:?Variable not set}", Map.of("PW", "")))
                .isEqualTo(new Result.Unset("PW", "Variable not set"));
        assertThat(resolve("${PW?must be set}", Map.of("PW", ""))).isEqualTo(new Result.Resolved(""));
        assertThat(resolve("${PW?must be set}", Map.of())).isEqualTo(new Result.Unset("PW", "must be set"));
    }

    @Test
    void anUnknownVariableWithoutDefaultIsNamed() {
        assertThat(resolve("x ${NOPE} y", Map.of())).isEqualTo(new Result.Unset("NOPE", ""));
    }

    @Test
    void replacementTextIsTakenLiterally() {
        // A value holding $ or \ must not be read as a regex replacement group.
        assertThat(resolve("${P}", Map.of("P", "a$1\\b"))).isEqualTo(new Result.Resolved("a$1\\b"));
    }
}
