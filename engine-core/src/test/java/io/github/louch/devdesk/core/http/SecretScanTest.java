package io.github.louch.devdesk.core.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SecretScanTest {

    @Test
    void typedTokensAreFoundAndVariablesAreFine() {
        var typed = new HttpCall(
                "GET",
                "{{api.url}}/orders?api_key=abc123",
                List.of(new HttpCall.Header("Authorization", "Bearer eyJhbGciOi"), new HttpCall.Header("Accept", "x")),
                new HttpCall.Body.None(),
                new HttpCall.Auth.Basic("ada", "hunter2"));
        var safe = new HttpCall(
                "GET",
                "{{api.url}}/orders?api_key={{key}}",
                List.of(new HttpCall.Header("Authorization", "Bearer {{token}}")),
                new HttpCall.Body.None(),
                new HttpCall.Auth.Bearer("{{token}}"));

        assertThat(SecretScan.of("List", typed))
                .containsExactly(
                        "List: header Authorization", "List: Basic password", "List: a key or token in the URL");
        assertThat(SecretScan.of("List", safe)).isEmpty();
        assertThat(SecretScan.of(
                        "Log in",
                        new HttpCall(
                                "POST",
                                "/login",
                                List.of(),
                                Optional.of("{\"user\": \"ada\", \"password\": \"dev\"}"))))
                .containsExactly("Log in: a password or token in the body");
        assertThat(SecretScan.of(
                        "Log in",
                        new HttpCall("POST", "/login", List.of(), Optional.of("{\"password\": \"{{password}}\"}"))))
                .isEmpty();
        assertThat(SecretScan.of("Health", new HttpCall("GET", "{{api.url}}/health", List.of(), Optional.empty())))
                .isEmpty();
    }
}
