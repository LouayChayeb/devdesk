package io.github.louch.devdesk.core.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProcessLaunchSpecTest {

    @Test
    void emptyCommandIsRejectedWithItsName() {
        assertThatThrownBy(() -> new ProcessLaunchSpec("api", "  ", Path.of("/tmp"), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'api'");
    }

    @Test
    void environmentIsCopiedSoLaterChangesDontLeakIn() {
        var env = new HashMap<String, String>();
        env.put("PORT", "3000");
        var spec = new ProcessLaunchSpec("api", "npm start", Path.of("/tmp"), env);
        env.put("PORT", "9999");

        assertThat(spec.environment()).containsEntry("PORT", "3000");
    }
}
