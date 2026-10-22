package io.github.louch.devdesk.core.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.louch.devdesk.core.config.ConfigParser;
import io.github.louch.devdesk.core.project.ProjectSpec;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RequestVariablesTest {

    static ProjectSpec project() throws Exception {
        return ConfigParser.parse("""
                version: 1
                services:
                  api: { command: node server.js, ports: [3001] }
                  db: { container: { image: postgres:18.1, ports: ["55432:5432"] } }
                  worker: { command: node worker.js }
                """, Path.of("/p"), Map.of(), f -> Optional.empty());
    }

    @Test
    void everyServiceWithAPortGetsUrlAndPort() throws Exception {
        assertThat(RequestVariables.ofServices(project()))
                .containsExactly(
                        Map.entry("api.url", "http://localhost:3001"),
                        Map.entry("api.port", "3001"),
                        Map.entry("db.url", "http://localhost:55432"),
                        Map.entry("db.port", "55432"));
    }

    @Test
    void placeholdersAreFilledEverywhereAndUnknownOnesReported() {
        var call = new HttpCall(
                "POST",
                "{{api.url}}/orders/{{ id }}",
                List.of(new HttpCall.Header("Authorization", "Bearer {{token}}")),
                Optional.of("{\"user\": \"{{user}}\"}"));

        var r = RequestVariables.resolve(call, Map.of("api.url", "http://localhost:3001", "id", "7", "token", "t0k"));

        assertThat(r.call().url()).isEqualTo("http://localhost:3001/orders/7");
        assertThat(r.call().headers()).containsExactly(new HttpCall.Header("Authorization", "Bearer t0k"));
        assertThat(r.call().text()).contains("{\"user\": \"{{user}}\"}");
        assertThat(r.missing()).containsExactly("user");
    }

    @Test
    void valuesWithDollarSignsAreTakenLiterally() {
        var r = RequestVariables.resolve(
                new HttpCall("GET", "{{a}}", List.of(), Optional.empty()), Map.of("a", "$1\\x"));
        assertThat(r.call().url()).isEqualTo("$1\\x");
    }
}
